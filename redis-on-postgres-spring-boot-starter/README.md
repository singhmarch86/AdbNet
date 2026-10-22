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

Needs a `DataSource` (from `spring-boot-starter-jdbc`) and your database's JDBC
driver — the **PostgreSQL** driver (`org.postgresql:postgresql`) or the
**MySQL/MariaDB** driver (`com.mysql:mysql-connector-j`). The dialect is detected
from the live connection, or set it explicitly with `pgredis.dialect`.

## Configure

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/app
    username: app
    password: secret

pgredis:
  dialect: auto              # auto | postgresql | mysql
  table-prefix: "mr_"        # mr_kv, mr_counter, mr_zset, mr_events
  initialize-on-startup: true
  ttl-check-on-read: true
  ttl-sweep-seconds: 60      # background deletion of expired rows; 0 disables
  pub-sub-poll-ms: 500       # poll interval for table-based pub/sub (MySQL path)
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
mvn -q test   # context + dialect-SQL tests (always) + Testcontainers integration tests (if Docker is up)
```

`PgRedisPostgresIntegrationTest` and `PgRedisMySqlIntegrationTest` share one set of
assertions (`AbstractSqlRedisIntegrationTest`) and run it against a real
`PostgreSQLContainer` and `MySQLContainer` respectively — covering KV/TTL, counters,
sorted sets, and both pub/sub strategies (LISTEN/NOTIFY and table-polling). Both are
`@Testcontainers(disabledWithoutDocker = true)`, so they run where Docker is available
and are **skipped** otherwise.

## Dialects (PostgreSQL + MySQL)

Database-specific SQL lives behind two seams, so the same `PgRedisTemplate` runs on
either database:

- **`io.pgredis.dialect.SqlDialect`** — upsert syntax, atomic counters, range
  pagination, and DDL. Implementations: `PostgresDialect`, `MySqlDialect`.
- **`io.pgredis.pubsub.PubSub`** — `PostgresPubSub` (native `LISTEN`/`NOTIFY`) and
  `PollingPubSub` (a polled `mr_events` table, for databases without native pub/sub).

| Concern        | `PostgresDialect`           | `MySqlDialect`                       |
|----------------|-----------------------------|--------------------------------------|
| Upsert         | `ON CONFLICT ... DO UPDATE` | `ON DUPLICATE KEY UPDATE`            |
| Counter        | `... RETURNING n`           | upsert + `SELECT` in one transaction |
| Range limit    | `LIMIT ALL`                 | `LIMIT <max> OFFSET <n>`             |
| Key column     | `rkey TEXT`                 | `rkey VARCHAR(255)` (avoids reserved `key`) |
| Pub/Sub        | native `LISTEN`/`NOTIFY`    | polled `mr_events` table             |

**Adding another database** (H2, SQL Server, …) means one new `SqlDialect` (and, if it
lacks native pub/sub, reuse `PollingPubSub`). Register it in
`PgRedisAutoConfiguration#resolveDialect`.

## Status

`0.1.0-SNAPSHOT` — v1 scope, with PostgreSQL and MySQL dialects. `mvn test` passes 6/6
(context wiring + pure dialect-SQL tests). Next: a Testcontainers integration suite
(real TTL/pub-sub against `PostgreSQLContainer` and `MySQLContainer`), shared with the
MongoDB module.
