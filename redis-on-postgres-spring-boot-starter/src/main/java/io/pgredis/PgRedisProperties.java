package io.pgredis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Redis-on-PostgreSQL starter.
 * Bound from properties prefixed with {@code pgredis.*}.
 */
@ConfigurationProperties(prefix = "pgredis")
public class PgRedisProperties {

    /** Prefix applied to every backing table (mr_kv, mr_counter, mr_zset). */
    private String tablePrefix = "mr_";

    /** Create tables and indexes on bean startup. */
    private boolean initializeOnStartup = true;

    /** Re-check expire_at on every read and treat an expired row as absent. */
    private boolean ttlCheckOnRead = true;

    /**
     * Interval in seconds for the background TTL sweeper that deletes expired
     * rows (PostgreSQL has no native TTL). 0 disables the sweeper.
     */
    private long ttlSweepSeconds = 60;

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
}
