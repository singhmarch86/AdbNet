package io.pgredis.dialect;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * PostgreSQL dialect: {@code ON CONFLICT} upserts, {@code RETURNING} counters,
 * {@code LIMIT ALL} ranges, and native pub/sub (LISTEN/NOTIFY — no events table).
 */
public class PostgresDialect implements SqlDialect {

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public boolean hasNativePubSub() {
        return true;
    }

    @Override
    public List<String> ddl(String kv, String counter, String zset, String events) {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + kv
                        + " (rkey TEXT PRIMARY KEY, value TEXT, expire_at TIMESTAMPTZ)",
                "CREATE INDEX IF NOT EXISTS " + kv + "_expire_idx ON " + kv + " (expire_at)",
                "CREATE TABLE IF NOT EXISTS " + counter
                        + " (rkey TEXT PRIMARY KEY, n BIGINT NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS " + zset
                        + " (k TEXT, m TEXT, score DOUBLE PRECISION NOT NULL, PRIMARY KEY (k, m))",
                "CREATE INDEX IF NOT EXISTS " + zset + "_k_score_idx ON " + zset + " (k, score)");
    }

    @Override
    public String upsertKv(String kv) {
        return "INSERT INTO " + kv + " (rkey, value, expire_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (rkey) DO UPDATE SET value = excluded.value, expire_at = excluded.expire_at";
    }

    @Override
    public String upsertZset(String zset) {
        return "INSERT INTO " + zset + " (k, m, score) VALUES (?, ?, ?) "
                + "ON CONFLICT (k, m) DO UPDATE SET score = excluded.score";
    }

    @Override
    public long incrBy(JdbcTemplate jdbc, String counter, String key, long n) {
        Long value = jdbc.queryForObject(
                "INSERT INTO " + counter + " (rkey, n) VALUES (?, ?) "
                        + "ON CONFLICT (rkey) DO UPDATE SET n = " + counter + ".n + excluded.n RETURNING n",
                Long.class, key, n);
        return value == null ? 0L : value;
    }

    @Override
    public String rangeSql(String zset, int skip, int limit, boolean asc) {
        String lim = (limit < 0) ? "ALL" : String.valueOf(limit);
        return "SELECT m, score FROM " + zset + " WHERE k = ? ORDER BY score "
                + (asc ? "ASC" : "DESC") + " OFFSET " + skip + " LIMIT " + lim;
    }
}
