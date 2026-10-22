package io.pgredis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Redis-on-SQL starter.
 * Bound from properties prefixed with {@code pgredis.*}.
 */
@ConfigurationProperties(prefix = "pgredis")
public class PgRedisProperties {

    /**
     * SQL dialect: {@code auto} (detect from the connection), {@code postgresql},
     * or {@code mysql} (also covers MariaDB).
     */
    private String dialect = "auto";

    /** Prefix applied to every backing table (mr_kv, mr_counter, mr_zset, mr_events). */
    private String tablePrefix = "mr_";

    /** Create tables and indexes on bean startup. */
    private boolean initializeOnStartup = true;

    /** Re-check expire_at on every read and treat an expired row as absent. */
    private boolean ttlCheckOnRead = true;

    /**
     * Interval in seconds for the background TTL sweeper that deletes expired
     * rows (SQL databases have no native TTL). 0 disables the sweeper.
     */
    private long ttlSweepSeconds = 60;

    /** Poll interval in milliseconds for table-based pub/sub (dialects without native pub/sub). */
    private long pubSubPollMs = 500;

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    public boolean isInitializeOnStartup() {
        return initializeOnStartup;
    }

    public void setInitializeOnStartup(boolean initializeOnStartup) {
        this.initializeOnStartup = initializeOnStartup;
    }

    public boolean isTtlCheckOnRead() {
        return ttlCheckOnRead;
    }

    public void setTtlCheckOnRead(boolean ttlCheckOnRead) {
        this.ttlCheckOnRead = ttlCheckOnRead;
    }

    public long getTtlSweepSeconds() {
        return ttlSweepSeconds;
    }

    public void setTtlSweepSeconds(long ttlSweepSeconds) {
        this.ttlSweepSeconds = ttlSweepSeconds;
    }

    public long getPubSubPollMs() {
        return pubSubPollMs;
    }

    public void setPubSubPollMs(long pubSubPollMs) {
        this.pubSubPollMs = pubSubPollMs;
    }
}
