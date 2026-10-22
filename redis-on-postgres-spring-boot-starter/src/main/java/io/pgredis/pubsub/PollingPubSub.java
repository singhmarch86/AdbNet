package io.pgredis.pubsub;

import io.pgredis.MessageListener;
import io.pgredis.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Portable pub/sub for databases without a native mechanism (MySQL and others):
 * {@code publish} inserts into an events table, {@code subscribe} polls for new
 * rows since the subscription started. At-most-once while a subscriber is up.
 */
public class PollingPubSub implements PubSub {

    private static final Logger log = LoggerFactory.getLogger(PollingPubSub.class);

    private final JdbcTemplate jdbc;
    private final String events;
    private final long pollMs;
    private final ScheduledExecutorService scheduler;

    public PollingPubSub(JdbcTemplate jdbc, String eventsTable, long pollMs) {
        this.jdbc = jdbc;
        this.events = eventsTable;
        this.pollMs = pollMs;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "pgredis-poll");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newScheduledThreadPool(2, tf);
    }

    @Override
    public void publish(String channel, String payload) {
        PubSub.requireValidChannel(channel);
        jdbc.update("INSERT INTO " + events + " (channel, payload, ts) VALUES (?, ?, now())",
                channel, payload);
    }

    @Override
    public Subscription subscribe(String channel, MessageListener listener) {
        PubSub.requireValidChannel(channel);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicLong lastId = new AtomicLong(currentMaxId());

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> poll(channel, listener, lastId), pollMs, pollMs, TimeUnit.MILLISECONDS);

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
                future.cancel(true);
            }
        };
    }

    private long currentMaxId() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + events, Long.class);
        return max == null ? 0L : max;
    }

    private void poll(String channel, MessageListener listener, AtomicLong lastId) {
        try {
            List<Object[]> rows = jdbc.query(
                    "SELECT id, payload FROM " + events + " WHERE channel = ? AND id > ? ORDER BY id",
                    (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)},
                    channel, lastId.get());
            for (Object[] row : rows) {
                lastId.set((Long) row[0]);
                listener.onMessage(channel, (String) row[1]);
            }
        } catch (RuntimeException e) {
            log.warn("poll of '{}' failed: {}", channel, e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
