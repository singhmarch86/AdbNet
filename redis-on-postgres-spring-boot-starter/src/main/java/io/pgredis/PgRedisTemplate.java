package io.pgredis;

import io.pgredis.codec.ValueCodec;
import io.pgredis.dialect.SqlDialect;
import io.pgredis.pubsub.PubSub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis-style primitives backed by a relational database. Data-structure SQL
 * that varies between databases is delegated to a {@link SqlDialect}; pub/sub
 * is delegated to a {@link PubSub} strategy. Portable statements live here.
 *
 * <p>Same API surface as the MongoDB variant, so backends are interchangeable.
 */
public class PgRedisTemplate implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgRedisTemplate.class);

    private final JdbcTemplate jdbc;
    private final PgRedisProperties props;
    private final ValueCodec codec;
    private final SqlDialect dialect;
    private final PubSub pubSub;

    private final String kv;
    private final String counter;
    private final String zset;
    private final String events;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pgredis-sweeper");
                t.setDaemon(true);
                return t;
            });

    public PgRedisTemplate(JdbcTemplate jdbc, PgRedisProperties props, ValueCodec codec,
                           SqlDialect dialect, PubSub pubSub) {
        this.jdbc = jdbc;
        this.props = props;
        this.codec = codec;
        this.dialect = dialect;
        this.pubSub = pubSub;
        String p = props.getTablePrefix();
        this.kv = p + "kv";
        this.counter = p + "counter";
        this.zset = p + "zset";
        this.events = p + "events";
    }

    public SqlDialect dialect() {
        return dialect;
    }

    // ------------------------------------------------------------------
    // Initialization: schema (dialect) + TTL sweeper (portable)
    // ------------------------------------------------------------------

    public void init() {
        for (String ddl : dialect.ddl(kv, counter, zset, events)) {
            jdbc.execute(ddl);
        }
        if (props.getTtlSweepSeconds() > 0) {
            long s = props.getTtlSweepSeconds();
            scheduler.scheduleAtFixedRate(this::sweepExpired, s, s, TimeUnit.SECONDS);
        }
        log.info("pgredis initialized (dialect={}, prefix={})", dialect.id(), props.getTablePrefix());
    }

    private void sweepExpired() {
        try {
            jdbc.update("DELETE FROM " + kv + " WHERE expire_at IS NOT NULL AND expire_at < now()");
        } catch (RuntimeException e) {
            log.warn("TTL sweep failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Key / Value
    // ------------------------------------------------------------------

    public void set(String key, Object value) {
        set(key, value, null);
    }

    public void set(String key, Object value, Duration ttl) {
        Timestamp expireAt = ttl == null ? null : Timestamp.from(Instant.now().plus(ttl));
        jdbc.update(dialect.upsertKv(kv), key, codec.encode(value), expireAt);
    }

    public String get(String key) {
        List<String[]> rows = jdbc.query(
                "SELECT value, expire_at FROM " + kv + " WHERE rkey = ?",
                (rs, i) -> new String[] {
                        rs.getString(1),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant().toString()
                }, key);
        if (rows.isEmpty()) {
            return null;
        }
        String[] row = rows.get(0);
        if (props.isTtlCheckOnRead() && row[1] != null && Instant.parse(row[1]).isBefore(Instant.now())) {
            del(key); // best-effort eager cleanup
            return null;
        }
        return row[0];
    }

    public <T> T get(String key, Class<T> type) {
        return codec.decode(get(key), type);
    }

    public boolean del(String key) {
        return jdbc.update("DELETE FROM " + kv + " WHERE rkey = ?", key) > 0;
    }

    public boolean exists(String key) {
        return get(key) != null;
    }

    // ------------------------------------------------------------------
    // TTL
    // ------------------------------------------------------------------

    public boolean expire(String key, Duration ttl) {
        Timestamp expireAt = Timestamp.from(Instant.now().plus(ttl));
        return jdbc.update("UPDATE " + kv + " SET expire_at = ? WHERE rkey = ?", expireAt, key) > 0;
    }

    /** -2 if key missing/expired, -1 if no expiry, else remaining seconds. */
    public long ttl(String key) {
        List<Timestamp> rows = jdbc.query(
                "SELECT expire_at FROM " + kv + " WHERE rkey = ?",
                (rs, i) -> rs.getTimestamp(1), key);
        if (rows.isEmpty()) {
            return -2;
        }
        Timestamp expireAt = rows.get(0);
        if (expireAt == null) {
            return -1;
        }
        Instant exp = expireAt.toInstant();
        if (exp.isBefore(Instant.now())) {
            return -2;
        }
        return Math.max(Duration.between(Instant.now(), exp).getSeconds(), 0);
    }

    // ------------------------------------------------------------------
    // Counters
    // ------------------------------------------------------------------

    public long incr(String key) {
        return incrBy(key, 1);
    }

    public long decr(String key) {
        return incrBy(key, -1);
    }

    public long incrBy(String key, long n) {
        return dialect.incrBy(jdbc, counter, key, n);
    }

    // ------------------------------------------------------------------
    // Sorted sets
    // ------------------------------------------------------------------

    public void zAdd(String key, String member, double score) {
        jdbc.update(dialect.upsertZset(zset), key, member, score);
    }

    public Double zScore(String key, String member) {
        List<Double> rows = jdbc.query(
                "SELECT score FROM " + zset + " WHERE k = ? AND m = ?",
                (rs, i) -> rs.getDouble(1), key, member);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean zRem(String key, String member) {
        return jdbc.update("DELETE FROM " + zset + " WHERE k = ? AND m = ?", key, member) > 0;
    }

    public List<Z> zRange(String key, int start, int stop) {
        return range(key, start, stop, true);
    }

    public List<Z> zRevRange(String key, int start, int stop) {
        return range(key, start, stop, false);
    }

    private List<Z> range(String key, int start, int stop, boolean asc) {
        int skip = Math.max(start, 0);
        if (stop >= 0 && (stop - skip + 1) <= 0) {
            return List.of();
        }
        int limit = (stop < 0) ? -1 : (stop - skip + 1);
        String sql = dialect.rangeSql(zset, skip, limit, asc);
        return jdbc.query(sql, (rs, i) -> new Z(rs.getString(1), rs.getDouble(2)), key);
    }

    /** 0-based rank by ascending score, or null if member absent. */
    public Long zRank(String key, String member) {
        Double score = zScore(key, member);
        if (score == null) {
            return null;
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + zset + " WHERE k = ? AND score < ?", Long.class, key, score);
    }

    // ------------------------------------------------------------------
    // Pub / Sub (delegated to the configured strategy)
    // ------------------------------------------------------------------

    public void publish(String channel, String payload) {
        pubSub.publish(channel, payload);
    }

    public Subscription subscribe(String channel, MessageListener listener) {
        return pubSub.subscribe(channel, listener);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        pubSub.close();
    }
}
