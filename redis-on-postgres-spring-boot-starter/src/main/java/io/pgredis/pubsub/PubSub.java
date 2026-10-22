package io.pgredis.pubsub;

import io.pgredis.MessageListener;
import io.pgredis.Subscription;

/**
 * Pub/sub strategy. One implementation uses the database's native mechanism
 * (PostgreSQL LISTEN/NOTIFY), another polls an events table (MySQL and others).
 */
public interface PubSub extends AutoCloseable {

    void publish(String channel, String payload);

    Subscription subscribe(String channel, MessageListener listener);

    @Override
    default void close() {
        // no-op by default
    }

    /** Channels must be safe to use as a SQL identifier (NOTIFY target). */
    static void requireValidChannel(String channel) {
        if (channel == null || !channel.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "Channel must match [A-Za-z0-9_]+ (got: " + channel + ")");
        }
    }
}
