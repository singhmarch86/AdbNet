package io.pgredis.dialect;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Abstracts the SQL that differs between relational databases: upsert syntax,
 * atomic counter semantics, range pagination, DDL, and whether the database
 * offers native pub/sub.
 *
 * <p>Portable statements (plain SELECT/DELETE/UPDATE by key) stay in the
 * template; only the dialect-specific pieces live here.
 */
public interface SqlDialect {

    /** Stable identifier, e.g. {@code "postgresql"} or {@code "mysql"}. */
    String id();

    /** Whether this database has a native pub/sub mechanism (e.g. LISTEN/NOTIFY). */
    boolean hasNativePubSub();

    /**
     * DDL statements to create the backing tables and indexes, in order.
     *
     * @param events name of the events table (used only when {@link #hasNativePubSub()} is false)
     */
    List<String> ddl(String kv, String counter, String zset, String events);

    /** Upsert for {@code mr_kv}. Bind params: rkey, value, expire_at. */
    String upsertKv(String kv);

    /** Upsert for {@code mr_zset}. Bind params: k, m, score. */
    String upsertZset(String zset);

    /** Atomically add {@code n} to the counter and return the new value. */
    long incrBy(JdbcTemplate jdbc, String counter, String key, long n);

    /**
     * Range query over a sorted set ordered by score, with one bind param (k).
     *
     * @param limit number of rows, or a negative value for "to the end"
     */
    String rangeSql(String zset, int skip, int limit, boolean asc);
}
