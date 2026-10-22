package io.pgredis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared primitive assertions, run against each concrete database subclass
 * (PostgreSQL, MySQL). Abstract, so the test runner does not execute it directly.
 */
abstract class AbstractSqlRedisIntegrationTest {

    /** The template under test, backed by the subclass's container. */
    abstract PgRedisTemplate ops();

    /** How long to wait after subscribing before publishing (pub/sub settle time). */
    long subscribeSettleMs() {
        return 1000;
    }

    @Test
    void kvRoundTrip() {
        PgRedisTemplate ops = ops();
        ops.set("k:1", "hello");
        assertThat(ops.get("k:1")).isEqualTo("hello");
        assertThat(ops.del("k:1")).isTrue();
        assertThat(ops.get("k:1")).isNull();
    }

    @Test
    void ttlExpiresOnRead() throws InterruptedException {
        PgRedisTemplate ops = ops();
        ops.set("s:1", "v", Duration.ofSeconds(1));
        assertThat(ops.get("s:1")).isEqualTo("v");
        assertThat(ops.ttl("s:1")).isBetween(0L, 1L);
        Thread.sleep(1200);
        assertThat(ops.get("s:1")).isNull(); // eager expiry check, before the sweeper
    }

    @Test
    void atomicCounter() {
        PgRedisTemplate ops = ops();
        assertThat(ops.incr("c:1")).isEqualTo(1);
        assertThat(ops.incrBy("c:1", 5)).isEqualTo(6);
        assertThat(ops.decr("c:1")).isEqualTo(5);
    }

    @Test
    void sortedSet() {
        PgRedisTemplate ops = ops();
        ops.zAdd("lb", "a", 10);
        ops.zAdd("lb", "b", 30);
        ops.zAdd("lb", "c", 20);
        assertThat(ops.zRevRange("lb", 0, 0)).extracting(Z::getMember).containsExactly("b");
        assertThat(ops.zRange("lb", 0, 2)).extracting(Z::getMember).containsExactly("a", "c", "b");
        assertThat(ops.zRange("lb", 0, -1)).hasSize(3);
        assertThat(ops.zRank("lb", "c")).isEqualTo(1L);
        assertThat(ops.zScore("lb", "b")).isEqualTo(30.0);
        assertThat(ops.zRem("lb", "a")).isTrue();
    }

    @Test
    void pubSubDeliversMessage() throws InterruptedException {
        PgRedisTemplate ops = ops();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();
        Subscription sub = ops.subscribe("events", (ch, msg) -> {
            received.set(msg);
            latch.countDown();
        });
        Thread.sleep(subscribeSettleMs());
        ops.publish("events", "ping");
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isEqualTo("ping");
        sub.close();
    }
}
