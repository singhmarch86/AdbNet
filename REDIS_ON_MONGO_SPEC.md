# Redis-on-MongoDB — Design Specification

**Status:** Draft v1
**Purpose:** Define a set of Redis-style primitives implemented as a *client-side library* on top of MongoDB, with one shared behavioral contract and idiomatic bindings for Spring (Java), Go, and Python.

---

## 1. Goal and non-goals

### Goal
Give applications standing on MongoDB a Redis-like API for the ~80% of use cases people reach for Redis to solve: **caching, sessions, atomic counters, leaderboards, and real-time events** — without running a separate Redis server.

### Non-goals
- **Not** a Redis server replacement or wire-protocol-compatible server.
- **Not** a MongoDB server plugin/extension. MongoDB exposes no public module API (unlike PostgreSQL's `CREATE EXTENSION`). All functionality lives in the application process.
- **Not** a claim of latency parity. MongoDB is a disk-backed document store; it will not match Redis's pure in-memory microsecond operations. This library trades some speed for "one fewer system to operate."

### Guiding principle
Each binding should *feel* like that ecosystem's native Redis client (`spring-data-redis`, `go-redis`, `redis-py`) so adoption is a near drop-in, while the behavioral differences below are documented loudly rather than hidden.

---

## 2. Architecture overview

```
   Application code
        │
        ▼
 ┌──────────────────┐     mirrors native Redis client API
 │  mongoredis lib  │     (per language: Spring / Go / Python)
 └──────────────────┘
        │  MongoDB driver
        ▼
 ┌──────────────────┐
 │     MongoDB      │  collections + indexes + Change Streams
 └──────────────────┘
```

- One **logical "database"** maps to one MongoDB database (configurable).
- Each **primitive family** (kv, zset, counter, pubsub, stream) maps to one or more collections with a defined schema and index set.
- The library owns **index creation** (idempotent, on startup or first use).

### Collection layout (default names, configurable prefix)

| Family            | Collection         | Notes                                  |
|-------------------|--------------------|----------------------------------------|
| Key/Value + TTL   | `mr_kv`            | one doc per key                        |
| Sorted sets       | `mr_zset`          | one doc per (key, member)              |
| Counters          | `mr_kv`            | reuse kv; value is numeric             |
| Pub/Sub           | `mr_events`        | capped; watched via Change Stream      |
| Streams/lists     | `mr_stream`        | capped collection                      |

---

## 3. Data model & index requirements

### 3.1 Key/Value (`mr_kv`)
```json
{
  "_id": "session:42",
  "value": <BSON any>,
  "type": "string|json|counter",
  "expireAt": ISODate("2026-10-22T12:30:00Z")  // optional
}
```
**Indexes**
- `_id` (implicit, unique).
- **TTL index:** `{ expireAt: 1 }` with `expireAfterSeconds: 0`. Mongo deletes the doc once `expireAt` passes.

### 3.2 Sorted set (`mr_zset`)
```json
{
  "_id": { "k": "leaderboard", "m": "alice" },   // compound _id = (key, member)
  "k": "leaderboard",
  "m": "alice",
  "score": 1500.0,
  "expireAt": ISODate(...)   // optional, whole-set expiry
}
```
**Indexes**
- Compound `_id` gives uniqueness per (key, member).
- `{ k: 1, score: 1 }` for range/rank queries.
- `{ expireAt: 1 }` TTL index (optional set-level expiry).

### 3.3 Pub/Sub (`mr_events`)
```json
{ "_id": ObjectId, "channel": "events", "payload": <any>, "ts": ISODate() }
```
- **Capped collection** (bounded size) so it self-trims.
- Subscribers use a **Change Stream** filtered by `channel`.

### 3.4 Stream / list (`mr_stream`)
```json
{ "_id": ObjectId, "key": "jobs", "payload": <any>, "ts": ISODate() }
```
- Capped collection; append via insert, read via tailing Change Stream or sorted scan.

---

## 4. Primitive → MongoDB mapping (the contract)

| Redis API          | Semantics                       | MongoDB implementation                                             |
|--------------------|---------------------------------|--------------------------------------------------------------------|
| `SET k v [EX s]`   | set value, optional TTL         | `replaceOne({_id:k}, {value,expireAt}, upsert)`                    |
| `GET k`            | read value                      | `findOne({_id:k})`; treat as absent if `expireAt` already passed  |
| `DEL k`            | delete                          | `deleteOne({_id:k})`                                               |
| `EXISTS k`         | presence                        | `countDocuments({_id:k}, limit 1)` (+ expiry check)               |
| `EXPIRE k s`       | set TTL                         | `updateOne({_id:k}, {$set:{expireAt: now+s}})`                    |
| `TTL k`            | remaining TTL                   | read `expireAt`, compute delta                                     |
| `INCR k` / `INCRBY`| atomic counter                  | `findOneAndUpdate({_id:k}, {$inc:{value:n}}, upsert, returnNew)`   |
| `ZADD k s m`       | add/update member score         | `updateOne({_id:{k,m}}, {$set:{score:s}}, upsert)`                |
| `ZSCORE k m`       | member score                    | `findOne({_id:{k,m}})`                                            |
| `ZRANGE k a b`     | rank range (asc)                | `find({k}).sort({score:1}).skip(a).limit(b-a+1)`                  |
| `ZREVRANGE`        | rank range (desc)               | same, `sort({score:-1})`                                          |
| `ZRANK k m`        | rank of member                  | `countDocuments({k, score: {$lt: memberScore}})`                 |
| `ZREM k m`         | remove member                   | `deleteOne({_id:{k,m}})`                                         |
| `PUBLISH ch msg`   | fire event                      | `insertOne({channel:ch, payload, ts})` into capped `mr_events`    |
| `SUBSCRIBE ch`     | receive events                  | `watch([{$match:{'fullDocument.channel':ch}}])` Change Stream     |
| `LPUSH`/`RPUSH`    | append                          | `insertOne` into capped `mr_stream`                              |

---

## 5. Documented behavioral differences (READ THIS)

These are the honest gaps vs. real Redis. They must be surfaced in each binding's docs and, where sensible, in method docstrings.

1. **TTL is not instant.** MongoDB's TTL monitor runs roughly every **60 seconds**, so an expired key may physically persist up to ~a minute. **Mitigation:** every read re-checks `expireAt` and treats a logically-expired doc as absent, so *observed* behavior is correct even before physical deletion.

2. **Pub/Sub durability differs — in both directions.**
   - Redis pub/sub is fire-and-forget: a subscriber that's offline misses messages.
   - Change Streams are **durable and resumable** via a resume token, so this library can actually offer *at-least-once* delivery and replay — a feature Redis pub/sub lacks.
   - Trade-off: Change Stream latency is **milliseconds**, not microseconds. Requires a MongoDB **replica set** (Change Streams don't work on standalone).

3. **Sorted-set performance.** Fine for moderate sets (thousands–low millions). For very large, very hot leaderboards, Redis's in-memory skiplist will substantially outperform indexed Mongo queries.

4. **Atomicity scope.** Single-document ops (`$inc`, `findOneAndUpdate`) are atomic. **Multi-key atomic operations** require MongoDB multi-document transactions, which are heavier than Redis's single-threaded atomicity. v1 should document that cross-key atomicity is a transaction, not free.

5. **No Lua-equivalent server-side scripting** across keys. Use the aggregation pipeline for server-side computation where possible.

6. **Latency floor.** Even with WiredTiger cache warm, expect microsecond→millisecond step-up vs. Redis. Not suitable as an L1 cache in latency-critical hot paths.

---

## 6. API surface — v1 scope

Ship exactly these in v1 (covers caching, sessions, counters, leaderboards, events):

- **KV:** `set(key, value, ttl?)`, `get(key)`, `del(key)`, `exists(key)`
- **TTL:** `expire(key, ttl)`, `ttl(key)`
- **Counter:** `incr(key)`, `incrBy(key, n)`, `decr(key)`
- **Sorted set:** `zadd(key, member, score)`, `zscore(key, member)`, `zrange(key, start, stop)`, `zrevrange(...)`, `zrank(key, member)`, `zrem(key, member)`
- **Pub/Sub:** `publish(channel, payload)`, `subscribe(channel, handler)`

Deferred to later versions: hashes, sets, bitmaps, HyperLogLog, full streams with consumer groups, `SCAN`, geo.

---

## 7. Per-language bindings

### 7.1 Spring (Java) — `redis-on-mongo-spring-boot-starter`
- Auto-configured `MongoRedisTemplate` bean; reuse app's existing `MongoClient`.
- API mirrors `spring-data-redis`'s `RedisTemplate` naming to minimize migration friction.
- TTL via `Duration`. Pub/Sub handler as a functional interface or `@EventListener`-style callback.
```java
ops.set("session:42", payload, Duration.ofMinutes(30));
ops.zAdd("leaderboard", "alice", 1500);
ops.subscribe("events", msg -> handle(msg));
```
- Config via `application.yml` (`mongoredis.database`, `mongoredis.collection-prefix`).

### 7.2 Go — `mongoredis`
- Interface-first; signatures mirror `go-redis` (`Set(ctx, key, val, ttl)`).
- Pub/Sub returns a channel; a goroutine pumps the Change Stream and resumes on error via resume token.
```go
c := mongoredis.New(mongoClient, "mydb")
c.Set(ctx, "session:42", payload, 30*time.Minute)
c.ZAdd(ctx, "leaderboard", mongoredis.Z{Member:"alice", Score:1500})
ch, _ := c.Subscribe(ctx, "events")
```

### 7.3 Python — `mongoredis` (async-first)
- Built on `motor`; mirror `redis-py` async API.
- `subscribe` is an async generator.
```python
c = MongoRedis(motor_client, db="mydb")
await c.set("session:42", payload, ex=1800)
await c.zadd("leaderboard", {"alice": 1500})
async for msg in c.subscribe("events"):
    handle(msg)
```

---

## 8. Cross-cutting requirements

- **Index management:** library creates all required indexes idempotently on init (configurable: eager vs. lazy). Never silently assume indexes exist.
- **Serialization:** pluggable value codec (default: native BSON / JSON). Keys are strings.
- **Error model:** map Mongo driver errors to a small, documented error taxonomy per language. Change Stream requires replica set — fail fast with a clear message on standalone.
- **Observability:** expose operation latency + Change Stream lag metrics (Micrometer / Prometheus / OpenTelemetry hooks).
- **Config knobs:** database name, collection prefix, TTL eager-check on read (on/off), capped collection sizes, pub/sub delivery mode (at-least-once with resume token vs. latest-only).

---

## 9. Testing & conformance

- **Shared conformance suite:** one language-agnostic test matrix (table of operation → expected result) that each binding must pass, keeping all three consistent.
- **Behavioral-difference tests:** explicitly assert the documented gaps (e.g. logically-expired-but-not-physically-deleted key reads as absent).
- **Requires** a replica-set MongoDB in CI (for Change Streams). Use the in-memory/ephemeral replica set for test speed.

---

## 10. Build order (recommended)

1. Write/ratify this spec.
2. Build the **Spring** binding first (clearest payoff, validates the design).
3. Extract the conformance suite from the Spring tests.
4. Port to **Go**, then **Python**, each passing the shared suite.

---

## 11. Decision log / open questions

- [ ] Pub/Sub default: at-least-once (resumable) or latest-only? Proposed default: **latest-only**, with at-least-once opt-in.
- [ ] Should `zrange` support score ranges (`ZRANGEBYSCORE`) in v1 or defer? Proposed: **defer**.
- [ ] Multi-key transaction helper in v1 or v2? Proposed: **v2**.
- [ ] Collection-per-key-family vs. single collection with discriminator? Proposed: **per-family** (cleaner indexes).
