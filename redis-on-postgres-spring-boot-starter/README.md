# redis-on-postgres-spring-boot-starter

Redis-style primitives — **KV with TTL, atomic counters, sorted sets, and pub/sub** —
backed by **PostgreSQL**, with the **same API** as the MongoDB variant so the two are
drop-in interchangeable.

> Part of the `redis-on-*` family. See [`../REDIS_ON_MONGO_SPEC.md`](../REDIS_ON_MONGO_SPEC.md)
> for the shared rationale. PostgreSQL is a strong fit because it has **native pub/sub**
> (`LISTEN`/`NOTIFY`) and clean upsert semantics (`ON CONFLICT`).

## Install

```xml
<dependency>
    <groupId>io.pgredis</groupId>
    <artifactId>redis-on-postgres-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Needs a `DataSource` (from `spring-boot-starter-jdbc`) and the PostgreSQL driver.

## Configure

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/app
    username: app
    password: secret

pgredis:
  table-prefix: "mr_"        # mr_kv, mr_counter, mr_zset
  initialize-on-startup: true
  ttl-check-on-read: true
  ttl-sweep-seconds: 60      # background deletion of expired rows; 0 disables
```

## Use

```java
@Service
class Example {
    private final PgRedisTemplate ops;

    Example(PgRedisTemplate ops) {
        this.ops = ops;
    }

    void demo() {
        ops.set("session:42", payload, Duration.ofMinutes(30));
        var p = ops.get("session:42", Payload.class);

        long hits = ops.incr("page:home:hits");

        ops.zAdd("leaderboard", "alice", 1500);
        List<Z> top10 = ops.zRevRange("leaderboard", 0, 9);

        Subscription sub = ops.subscribe("events", (ch, msg) -> handle(msg));
        ops.publish("events", "hello");   // NOTIFY
    }
}
```

## Primitive → PostgreSQL mapping

| Redis API      | PostgreSQL implementation |
|----------------|---------------------------|
| `SET k v EX s` | `INSERT ... ON CONFLICT (key) DO UPDATE` with `expire_at` |
| `GET` / `DEL`  | `SELECT` / `DELETE` by primary key (+ expiry check) |
| `EXPIRE/TTL`   | `UPDATE`/`SELECT` of `expire_at` column |
| `INCR/INCRBY`  | `INSERT ... ON CONFLICT DO UPDATE SET n = n + ? RETURNING n` (atomic) |
| `ZADD`         | `INSERT ... ON CONFLICT (k,m) DO UPDATE SET score` |
| `ZRANGE`       | `ORDER BY score OFFSET ? LIMIT ?` |
| `ZRANK`        | `SELECT count(*) WHERE k=? AND score < ?` |
| `PUBLISH`      | `pg_notify(channel, payload)` |
| `SUBSCRIBE`    | dedicated connection + `LISTEN` + `getNotifications()` |

## Behavioral differences

1. **TTL** is emulated: an `expire_at` column + a sweeper (`ttl-sweep-seconds`). Reads
   re-check `expire_at` so an expired row reads as absent before the sweep runs.
2. **Pub/Sub** is native and low-latency, but a NOTIFY **payload is capped at 8000 bytes**
   and delivery is at-most-once (a subscriber that's down misses messages). For large or
   durable messages, store the body in a table and NOTIFY only an id.
3. **`subscribe` holds a dedicated connection** for the subscription's lifetime. Use a
   datasource/pool sized to account for long-lived listener connections.
4. **Atomicity** covers single statements. Cross-key atomicity uses a normal SQL
   transaction (out of v1 scope as a helper).

## Build & test

```bash
mvn -q test   # context smoke tests, no live PostgreSQL needed
```

Integration tests (real TTL/pub-sub) run against Testcontainers' `PostgreSQLContainer`.

## Extending to other relational databases

The SQL used here is *mostly* portable; two things are dialect-specific and would move
behind a small `SqlDialect` seam in a future version:

| Concern        | PostgreSQL        | MySQL / MariaDB               | H2 / others |
|----------------|-------------------|-------------------------------|-------------|
| Upsert         | `ON CONFLICT ... DO UPDATE` | `ON DUPLICATE KEY UPDATE` | `MERGE` |
| Counter return | `RETURNING n`     | second `SELECT` in a txn      | `RETURNING` (H2) |
| Pub/Sub        | `LISTEN`/`NOTIFY` (native) | **none** — poll a message table or use an external bus | none |

So KV/TTL, counters, and sorted sets port cleanly to MySQL with just an upsert-dialect
swap. Pub/sub is the only piece without a native MySQL equivalent — there it falls back
to a polled `mr_events` table (the same shape as the MongoDB capped-collection approach).

## Status

`0.1.0-SNAPSHOT` — scaffold, v1 scope. Next: the `SqlDialect` seam + a MySQL dialect,
and the Testcontainers integration suite shared with the MongoDB module.
