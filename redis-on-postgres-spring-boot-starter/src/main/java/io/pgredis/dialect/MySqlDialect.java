package io.pgredis.dialect;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * MySQL / MariaDB dialect: {@code ON DUPLICATE KEY UPDATE} upserts, a
 * transactional increment (no {@code RETURNING}), {@code LIMIT n OFFSET m}
 * ranges, and no native pub/sub — so a polled events table is created.
 */
public class MySqlDialect implements SqlDialect {

    // MySQL's maximum BIGINT UNSIGNED, used as an "unbounded" LIMIT with OFFSET.
    private static final String UNBOUNDED = "18446744073709551615";

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public boolean hasNativePubSub() {
        return false;
    }

    @Override
    public List<String> ddl(String kv, String counter, String zset, String events) {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + kv + " ("
                        + "rkey VARCHAR(255) PRIMARY KEY, value LONGTEXT, expire_at DATETIME(3), "
                        + "INDEX " + kv + "_expire_idx (expire_at))",
                "CREATE TABLE IF NOT EXISTS " + counter + " ("
                        + "rkey VARCHAR(255) PRIMARY KEY, n BIGINT NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS " + zset + " ("
                        + "k VARCHAR(255), m VARCHAR(255), score DOUBLE NOT NULL, "
                        + "PRIMARY KEY (k, m), INDEX " + zset + "_k_score_idx (k, score))",
                "CREATE TABLE IF NOT EXISTS " + events + " ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, channel VARCHAR(255), "
                        + "payload LONGTEXT, ts DATETIME(3), INDEX " + events + "_ch_idx (channel, id))");
    }

    @Override
    public String upsertKv(String kv) {
        return "INSERT INTO " + kv + " (rkey, value, expire_at) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE value = VALUES(value), expire_at = VALUES(expire_at)";
    }

    @Override
    public String upsertZset(String zset) {
        return "INSERT INTO " + zset + " (k, m, score) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE score = VALUES(score)";
    }

    @Override
    public long incrBy(JdbcTemplate jdbc, String counter, String key, long n) {
        // No RETURNING in MySQL: upsert then read back, on one connection in a
        // single transaction so the read reflects this transaction's increment.
        Long result = jdbc.execute((Connection c) -> {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement up = c.prepareStatement(
                        "INSERT INTO " + counter + " (rkey, n) VALUES (?, ?) "
                                + "ON DUPLICATE KEY UPDATE n = n + VALUES(n)")) {
                    up.setString(1, key);
                    up.setLong(2, n);
                    up.executeUpdate();
                }
                long value;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT n FROM " + counter + " WHERE rkey = ?")) {
                    sel.setString(1, key);
                    try (ResultSet rs = sel.executeQuery()) {
                        value = rs.next() ? rs.getLong(1) : 0L;
                    }
                }
                c.commit();
                return value;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        });
        return result == null ? 0L : result;
    }

    @Override
    public String rangeSql(String zset, int skip, int limit, boolean asc) {
        String lim = (limit < 0) ? UNBOUNDED : String.valueOf(limit);
        return "SELECT m, score FROM " + zset + " WHERE k = ? ORDER BY score "
                + (asc ? "ASC" : "DESC") + " LIMIT " + lim + " OFFSET " + skip;
    }
}
