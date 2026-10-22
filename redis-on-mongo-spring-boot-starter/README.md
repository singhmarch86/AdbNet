# redis-on-mongo-spring-boot-starter

Redis-style primitives — **KV with TTL, atomic counters, sorted sets, and pub/sub** —
backed by **MongoDB**, exposed as a Spring Boot auto-configured bean that feels like
`spring-data-redis`' `RedisTemplate`.

> For teams already on MongoDB who want Redis semantics without running a second
> datastore. It is **not** a Redis replacement and does not match Redis latency —
> see [Behavioral differences](#behavioral-differences). Full rationale and the
> primitive→MongoDB mapping live in [`../REDIS_ON_MONGO_SPEC.md`](../REDIS_ON_MONGO_SPEC.md).

## Install

```xml
<dependency>
    <groupId>io.mongoredis</groupId>
    <artifactId>redis-on-mongo-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

You also need `spring-boot-starter-data-mongodb` on the classpath (it provides the
`MongoClient` the starter wires from). Pub/sub requires a **replica set** (Change Streams).

## Configure

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017

mongoredis:
  database: mongoredis        # database the primitives live in
  collection-prefix: "mr_"    # mr_kv, mr_zset, mr_events
  initialize-on-startup: true # create indexes + capped collection
  ttl-check-on-read: true     # treat logically-expired docs as absent
  capped-events-size-bytes: 16777216
```

## Use

```java
@Service
class Example {
    private final MongoRedisTemplate ops;

    Example(MongoRedisTemplate ops) {
        this.ops = ops;
    }

    void demo() {
        // KV + TTL
        ops.set("session:42", payload, Duration.ofMinutes(30));
        var p = ops.get("session:42", Payload.class);
        long remaining = ops.ttl("session:42");

        // Atomic counter
        long hits = ops.incr("page:home:hits");

        // Sorted set / leaderboard
        ops.zAdd("leaderboard", "alice", 1500);
        List<Z> top10 = ops.zRevRange("leaderboard", 0, 9);
        Long rank = ops.zRank("leaderboard", "alice");

        // Pub/Sub (requires a replica set)
        Subscription sub = ops.subscribe("events", (ch, msg) -> handle(msg));
        ops.publish("events", "hello");
        // sub.close(); when done
    }
}
```

## API (v1)

| Family      | Methods |
|-------------|---------|
| KV          | `set`, `get`, `get(key, Class)`, `del`, `exists` |
| TTL         | `expire`, `ttl` |
| Counter     | `incr`, `decr`, `incrBy` |
| Sorted set  | `zAdd`, `zScore`, `zRange`, `zRevRange`, `zRank`, `zRem` |
| Pub/Sub     | `publish`, `subscribe` |

## Behavioral differences

These are intentional and documented — do not assume Redis parity:

1. **TTL is not instant.** MongoDB's TTL monitor runs ~every 60s. With
   `ttl-check-on-read=true` (default) an expired key still *reads* as absent before
   physical deletion.
2. **Pub/Sub latency is milliseconds**, not microseconds, and requires a replica set.
   On the upside, Change Streams are resumable (Redis pub/sub is fire-and-forget).
3. **Sorted sets** are fine for moderate sizes; a very large, very hot leaderboard
   will favour Redis' in-memory skiplist.
4. **Atomicity** covers single documents (`incr`, `zAdd`). Cross-key atomicity needs
   a MongoDB transaction and is out of v1 scope.

## Build & test

```bash
mvn -q test   # context smoke tests, no live MongoDB needed
```

Integration tests against pub/sub / TTL require a replica-set MongoDB (e.g. a
Testcontainers `MongoDBContainer` started as a replica set).

## Status

`0.1.0-SNAPSHOT` — scaffold. v1 scope per the design spec. Later: hashes, sets,
`ZRANGEBYSCORE`, streams with consumer groups, multi-key transaction helper.
