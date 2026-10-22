package io.pgredis;

import io.pgredis.codec.JacksonValueCodec;
import io.pgredis.codec.ValueCodec;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Redis-style primitives backed by PostgreSQL. Same API surface as the
 * MongoDB variant, so the two are interchangeable from the application's view.
 *
 * <p>Pub/sub uses native {@code LISTEN}/{@code NOTIFY}; TTL is emulated with an
 * {@code expire_at} column plus a background sweeper (Postgres has no native TTL).
 */
public class PgRedisTemplate implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgRedisTemplate.class);
    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z0-9_]+");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final PgRedisProperties props;
    private final ValueCodec codec;

    private final String kv;
    private final String counter;
    private final String zset;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(daemon("pgredis-sweeper"));
    private final ScheduledExecutorService subscribers =
            Executors.newScheduledThreadPool(2, daemon("pgredis-sub"));

    public PgRedisTemplate(DataSource dataSource, PgRedisProperties props) {
        this(dataSource, props, new JacksonValueCodec());
    }

    public PgRedisTemplate(DataSource dataSource, PgRedisProperties props, ValueCodec codec) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.props = props;
        this.codec = codec;
        String p = props.getTablePrefix();
        this.kv = p + "kv";
        this.counter = p + "counter";
        this.zset = p + "zset";
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------
    // Initialization: schema + TTL sweeper
    // ------------------------------------------------------------------

    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + kv
                + " (key TEXT PRIMARY KEY, value TEXT, expire_at TIMESTAMPTZ)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS " + kv + "_expire_idx ON " + kv + " (expire_at)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + counter
                + " (key TEXT PRIMARY KEY, n BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + zset
                + " (k TEXT, m TEXT, score DOUBLE PRECISION NOT NULL, PRIMARY KEY (k, m))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS " + zset + "_k_score_idx ON " + zset + " (k, score)");

        if (props.getTtlSweepSeconds() > 0) {
            long s = props.getTtlSweepSeconds();
            scheduler.scheduleAtFixedRate(this::sweepExpired, s, s, TimeUnit.SECONDS);
        }
        log.info("pgredis initialized (prefix={})", props.getTablePrefix());
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
        jdbc.update("INSERT INTO " + kv + " (key, value, expire_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (key) DO UPDATE SET value = excluded.value, expire_at = excluded.expire_at",
                key, codec.encode(value), expireAt);
    }

    public String get(String key) {
        List<String[]> rows = jdbc.query(
                "SELECT value, expire_at FROM " + kv + " WHERE key = ?",
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
        return jdbc.update("DELETE FROM " + kv + " WHERE key = ?", key) > 0;
    }

    public boolean exists(String key) {
        return get(key) != null;
    }

    // ------------------------------------------------------------------
    // TTL
    // ------------------------------------------------------------------

    public boolean expire(String key, Duration ttl) {
        Timestamp expireAt = Timestamp.from(Instant.now().plus(ttl));
        return jdbc.update("UPDATE " + kv + " SET expire_at = ? WHERE key = ?", expireAt, key) > 0;
    }

    /** -2 if key missing/expired, -1 if no expiry, else remaining seconds. */
    public long ttl(String key) {
        List<Timestamp> rows = jdbc.query(
                "SELECT expire_at FROM " + kv + " WHERE key = ?",
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
    // Counters (atomic upsert)
    // ------------------------------------------------------------------

    public long incr(String key) {
        return incrBy(key, 1);
    }

    public long decr(String key) {
        return incrBy(key, -1);
    }

    public long incrBy(String key, long n) {
        Long value = jdbc.queryForObject(
                "INSERT INTO " + counter + " (key, n) VALUES (?, ?) "
                        + "ON CONFLICT (key) DO UPDATE SET n = " + counter + ".n + excluded.n RETURNING n",
                Long.class, key, n);
        return value == null ? 0L : value;
    }

    // ------------------------------------------------------------------
    // Sorted sets
    // ------------------------------------------------------------------

    public void zAdd(String key, String member, double score) {
        jdbc.update("INSERT INTO " + zset + " (k, m, score) VALUES (?, ?, ?) "
                        + "ON CONFLICT (k, m) DO UPDATE SET score = excluded.score",
                key, member, score);
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
            return new ArrayList<>();
        }
        String limit = (stop < 0) ? "ALL" : String.valueOf(stop - skip + 1);
        String sql = "SELECT m, score FROM " + zset + " WHERE k = ? ORDER BY score "
                + (asc ? "ASC" : "DESC") + " OFFSET " + skip + " LIMIT " + limit;
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
    // Pub / Sub (native LISTEN / NOTIFY)
    // ------------------------------------------------------------------

    /** PUBLISH channel payload. Payload is limited to 8000 bytes by PostgreSQL. */
    public void publish(String channel, String payload) {
        requireValidChannel(channel);
        jdbc.queryForObject("SELECT pg_notify(?, ?)", String.class, channel, payload);
    }

    /** SUBSCRIBE channel — holds a dedicated connection for the subscription's life. */
    public Subscription subscribe(String channel, MessageListener listener) {
        requireValidChannel(channel);
        AtomicBoolean active = new AtomicBoolean(true);
        final Connection conn;
        try {
            conn = dataSource.getConnection();
            PGConnection pg = conn.unwrap(PGConnection.class);
            try (Statement st = conn.createStatement()) {
                st.execute("LISTEN \"" + channel + "\"");
            }
            subscribers.submit(() -> pump(channel, listener, active, conn, pg));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to subscribe to '" + channel + "'", e);
        }

        return new Subscription() {
            @Override
            public String channel() {
                return channel;
            }

            @Override
            public boolean isActive() {
                return active.get();
            }

            @Override
            public void close() {
                active.set(false);
                closeQuietly(conn);
            }
        };
    }

    private void pump(String channel, MessageListener listener, AtomicBoolean active,
                      Connection conn, PGConnection pg) {
        try {
            while (active.get()) {
                PGNotification[] notes = pg.getNotifications(500);
                if (notes != null) {
                    for (PGNotification n : notes) {
                        listener.onMessage(n.getName(), n.getParameter());
                    }
                }
            }
        } catch (Exception e) {
            if (active.get()) {
                log.warn("subscription to '{}' ended: {}", channel, e.getMessage());
            }
        } finally {
            closeQuietly(conn);
        }
    }

    private static void requireValidChannel(String channel) {
        if (channel == null || !CHANNEL.matcher(channel).matches()) {
            throw new IllegalArgumentException(
                    "Channel must match [A-Za-z0-9_]+ (got: " + channel + ")");
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (Exception ignore) {
            // best effort
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        subscribers.shutdownNow();
    }
}
