package io.mongoredis;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests against a real MongoDB (replica set, required for Change
 * Streams). Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoRedisIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    static MongoClient client;
    static MongoRedisTemplate ops;

    @BeforeAll
    static void setUp() {
        client = MongoClients.create(MONGO.getConnectionString());
        MongoRedisProperties props = new MongoRedisProperties();
        props.setDatabase("itest");
        ops = new MongoRedisTemplate(client, props);
        ops.init();
    }

    @AfterAll
    static void tearDown() {
        if (ops != null) {
            ops.close();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void kvRoundTrip() {
        ops.set("k:1", "hello");
        assertThat(ops.get("k:1")).isEqualTo("hello");
        assertThat(ops.del("k:1")).isTrue();
        assertThat(ops.get("k:1")).isNull();
    }

    @Test
    void ttlExpiresOnRead() throws InterruptedException {
        ops.set("s:1", "v", Duration.ofSeconds(1));
        assertThat(ops.get("s:1")).isEqualTo("v");
        Thread.sleep(1200);
        assertThat(ops.get("s:1")).isNull(); // eager expiry check, before the TTL monitor
    }

    @Test
    void atomicCounter() {
        assertThat(ops.incr("c:1")).isEqualTo(1);
        assertThat(ops.incrBy("c:1", 5)).isEqualTo(6);
        assertThat(ops.decr("c:1")).isEqualTo(5);
    }

    @Test
    void sortedSet() {
        ops.zAdd("lb", "a", 10);
        ops.zAdd("lb", "b", 30);
        ops.zAdd("lb", "c", 20);
        assertThat(ops.zRevRange("lb", 0, 0)).extracting(Z::getMember).containsExactly("b");
        assertThat(ops.zRange("lb", 0, 2)).extracting(Z::getMember).containsExactly("a", "c", "b");
        assertThat(ops.zRank("lb", "c")).isEqualTo(1L);
        assertThat(ops.zScore("lb", "b")).isEqualTo(30.0);
    }

    @Test
    void pubSubDeliversMessage() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Object> received = new AtomicReference<>();
        Subscription sub = ops.subscribe("events", (ch, msg) -> {
            received.set(msg);
            latch.countDown();
        });
        Thread.sleep(1000); // let the Change Stream establish before publishing
        ops.publish("events", "ping");
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isEqualTo("ping");
        sub.close();
    }
}
