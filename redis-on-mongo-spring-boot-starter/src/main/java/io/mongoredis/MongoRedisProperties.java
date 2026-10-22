package io.mongoredis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Redis-on-MongoDB starter.
 *
 * <p>Bound from properties prefixed with {@code mongoredis.*}.
 */
@ConfigurationProperties(prefix = "mongoredis")
public class MongoRedisProperties {

    /** MongoDB database the primitives live in. */
    private String database = "mongoredis";

    /** Prefix applied to every backing collection (mr_kv, mr_zset, mr_events). */
    private String collectionPrefix = "mr_";

    /** Create indexes and capped collections on bean startup. */
    private boolean initializeOnStartup = true;

    /**
     * Re-check {@code expireAt} on every read and treat a logically expired
     * document as absent. Compensates for MongoDB's ~60s TTL monitor interval.
     */
    private boolean ttlCheckOnRead = true;

    /** Size in bytes of the capped collection backing pub/sub. */
    private long cappedEventsSizeBytes = 16L * 1024 * 1024;

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getCollectionPrefix() {
        return collectionPrefix;
    }

    public void setCollectionPrefix(String collectionPrefix) {
        this.collectionPrefix = collectionPrefix;
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

    public long getCappedEventsSizeBytes() {
        return cappedEventsSizeBytes;
    }

    public void setCappedEventsSizeBytes(long cappedEventsSizeBytes) {
        this.cappedEventsSizeBytes = cappedEventsSizeBytes;
    }
}
