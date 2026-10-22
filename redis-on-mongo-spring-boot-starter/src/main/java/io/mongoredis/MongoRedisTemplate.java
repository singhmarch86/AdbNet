package io.mongoredis;

import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.mongoredis.codec.JacksonValueCodec;
import io.mongoredis.codec.ValueCodec;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-style primitives backed by MongoDB. Mirrors the common surface of
 * {@code spring-data-redis}' {@code RedisTemplate} for an easy migration.
 *
 * <p>See the design spec for the full primitive &rarr; MongoDB mapping and the
 * documented behavioral differences (TTL granularity, pub/sub durability, etc.).
 */
public class MongoRedisTemplate implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoRedisTemplate.class);

    private final MongoDatabase db;
    private final MongoRedisProperties props;
    private final ValueCodec codec;

    private final MongoCollection<Document> kv;
    private final MongoCollection<Document> zset;
    private final MongoCollection<Document> events;

    private final ExecutorService subscribers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mongoredis-sub");
        t.setDaemon(true);
        return t;
    });

    public MongoRedisTemplate(MongoClient client, MongoRedisProperties props) {
        this(client, props, new JacksonValueCodec());
    }

    public MongoRedisTemplate(MongoClient client, MongoRedisProperties props, ValueCodec codec) {
        this.props = props;
        this.codec = codec;
        this.db = client.getDatabase(props.getDatabase());
        String p = props.getCollectionPrefix();
        this.kv = db.getCollection(p + "kv");
        this.zset = db.getCollection(p + "zset");
        this.events = db.getCollection(p + "events");
    }

    // ------------------------------------------------------------------
    // Initialization: indexes + capped collection (idempotent)
    // ------------------------------------------------------------------

    public void init() {
        kv.createIndex(Indexes.ascending("expireAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
        zset.createIndex(Indexes.ascending("k", "score"));
        zset.createIndex(Indexes.ascending("expireAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
        ensureCappedEvents();
        log.info("mongoredis initialized (db={}, prefix={})", props.getDatabase(), props.getCollectionPrefix());
    }

    private void ensureCappedEvents() {
        String name = props.getCollectionPrefix() + "events";
        for (String existing : db.listCollectionNames()) {
            if (existing.equals(name)) {
                return; // already created (possibly capped) — leave as-is
            }
        }
        try {
            db.createCollection(name, new CreateCollectionOptions()
                    .capped(true)
                    .sizeInBytes(props.getCappedEventsSizeBytes()));
        } catch (RuntimeException e) {
            // Lost a race with another instance creating it; safe to ignore.
            log.debug("capped events collection already present: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Key / Value
    // ------------------------------------------------------------------

    /** SET key value */
    public void set(String key, Object value) {
        set(key, value, null);
    }

    /** SET key value EX ttl */
    public void set(String key, Object value, Duration ttl) {
        Document doc = new Document("_id", key).append("value", codec.encode(value));
        if (ttl != null) {
            doc.append("expireAt", Date.from(Instant.now().plus(ttl)));
        }
        kv.replaceOne(Filters.eq("_id", key), doc, new ReplaceOptions().upsert(true));
    }

    /** GET key — raw stored value, or null if absent/expired. */
    public Object get(String key) {
        Document doc = kv.find(Filters.eq("_id", key)).first();
        if (doc == null) {
            return null;
        }
        if (isExpired(doc)) {
            kv.deleteOne(Filters.eq("_id", key)); // best-effort eager cleanup
            return null;
        }
        return doc.get("value");
    }

    /** GET key decoded into the requested type. */
    public <T> T get(String key, Class<T> type) {
        return codec.decode(get(key), type);
    }

    /** DEL key — returns true if a document was removed. */
    public boolean del(String key) {
        return kv.deleteOne(Filters.eq("_id", key)).getDeletedCount() > 0;
    }

    /** EXISTS key */
    public boolean exists(String key) {
        return get(key) != null;
    }

    // ------------------------------------------------------------------
    // TTL
    // ------------------------------------------------------------------

    /** EXPIRE key ttl — returns true if the key existed. */
    public boolean expire(String key, Duration ttl) {
        Bson update = Updates.set("expireAt", Date.from(Instant.now().plus(ttl)));
        return kv.updateOne(Filters.eq("_id", key), update).getMatchedCount() > 0;
    }

    /**
     * TTL key — remaining seconds. Redis semantics:
     * -2 if the key does not exist, -1 if it exists without an expiry.
     */
    public long ttl(String key) {
        Document doc = kv.find(Filters.eq("_id", key)).first();
        if (doc == null || isExpired(doc)) {
            return -2;
        }
        Date expireAt = doc.getDate("expireAt");
        if (expireAt == null) {
            return -1;
        }
        long remaining = Duration.between(Instant.now(), expireAt.toInstant()).getSeconds();
        return Math.max(remaining, 0);
    }

    private boolean isExpired(Document doc) {
        if (!props.isTtlCheckOnRead()) {
            return false;
        }
        Date expireAt = doc.getDate("expireAt");
        return expireAt != null && expireAt.toInstant().isBefore(Instant.now());
    }

    // ------------------------------------------------------------------
    // Counters (atomic via single-document $inc)
    // ------------------------------------------------------------------

    /** INCR key */
    public long incr(String key) {
        return incrBy(key, 1);
    }

    /** DECR key */
    public long decr(String key) {
        return incrBy(key, -1);
    }

    /** INCRBY key n — returns the value after increment. */
    public long incrBy(String key, long n) {
        Document result = kv.findOneAndUpdate(
                Filters.eq("_id", key),
                Updates.combine(Updates.inc("value", n), Updates.set("type", "counter")),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        Number value = (result == null) ? null : (Number) result.get("value");
        return value == null ? 0L : value.longValue();
    }

    // ------------------------------------------------------------------
    // Sorted sets
    // ------------------------------------------------------------------

    /** ZADD key score member */
    public void zAdd(String key, String member, double score) {
        Document id = new Document("k", key).append("m", member);
        Bson update = Updates.combine(
                Updates.set("k", key),
                Updates.set("m", member),
                Updates.set("score", score));
        zset.updateOne(Filters.eq("_id", id), update, new UpdateOptions().upsert(true));
    }

    /** ZSCORE key member — null if absent. */
    public Double zScore(String key, String member) {
        Document doc = zset.find(Filters.eq("_id", new Document("k", key).append("m", member))).first();
        return doc == null ? null : doc.getDouble("score");
    }

    /** ZREM key member — returns true if removed. */
    public boolean zRem(String key, String member) {
        Document id = new Document("k", key).append("m", member);
        return zset.deleteOne(Filters.eq("_id", id)).getDeletedCount() > 0;
    }

    /**
     * ZRANGE key start stop — members by ascending score, 0-based inclusive
     * indices. {@code stop = -1} means "to the end".
     */
    public List<Z> zRange(String key, int start, int stop) {
        return range(key, start, stop, Sorts.ascending("score"));
    }

    /** ZREVRANGE key start stop — members by descending score. */
    public List<Z> zRevRange(String key, int start, int stop) {
        return range(key, start, stop, Sorts.descending("score"));
    }

    private List<Z> range(String key, int start, int stop, Bson sort) {
        int skip = Math.max(start, 0);
        int limit = (stop < 0) ? 0 : (stop - skip + 1); // limit(0) == unbounded
        List<Z> out = new ArrayList<>();
        if (stop >= 0 && limit <= 0) {
            return out;
        }
        for (Document d : zset.find(Filters.eq("k", key)).sort(sort).skip(skip).limit(limit)) {
            out.add(new Z(d.getString("m"), d.getDouble("score")));
        }
        return out;
    }

    /** ZRANK key member — 0-based rank by ascending score, or null if absent. */
    public Long zRank(String key, String member) {
        Double score = zScore(key, member);
        if (score == null) {
            return null;
        }
        return zset.countDocuments(Filters.and(Filters.eq("k", key), Filters.lt("score", score)));
    }

    // ------------------------------------------------------------------
    // Pub / Sub (Change Streams — requires a replica set)
    // ------------------------------------------------------------------

    /** PUBLISH channel payload */
    public void publish(String channel, Object payload) {
        events.insertOne(new Document("channel", channel)
                .append("payload", codec.encode(payload))
                .append("ts", new Date()));
    }

    /**
     * SUBSCRIBE channel — invokes {@code listener} for each future message.
     * Returns a {@link Subscription} to stop listening.
     */
    public Subscription subscribe(String channel, MessageListener listener) {
        AtomicBoolean active = new AtomicBoolean(true);
        List<Bson> pipeline = List.of(Aggregates.match(Filters.and(
                Filters.eq("operationType", "insert"),
                Filters.eq("fullDocument.channel", channel))));

        subscribers.submit(() -> {
            try {
                ChangeStreamIterable<Document> stream = events.watch(pipeline);
                for (var change : stream) {
                    if (!active.get()) {
                        break;
                    }
                    Document full = change.getFullDocument();
                    if (full != null) {
                        listener.onMessage(channel, full.get("payload"));
                    }
                }
            } catch (RuntimeException e) {
                if (active.get()) {
                    log.warn("subscription to '{}' ended: {}", channel, e.getMessage());
                }
            }
        });

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
            }
        };
    }

    @Override
    public void close() {
        subscribers.shutdownNow();
    }
}
