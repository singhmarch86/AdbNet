package io.pgredis;

/**
 * Handle to an active pub/sub subscription. Closing it issues {@code UNLISTEN}
 * and releases the dedicated connection and worker thread.
 */
public interface Subscription extends AutoCloseable {

    /** The channel this subscription listens on. */
    String channel();

    /** Whether the subscription is still active. */
    boolean isActive();

    /** Stop listening and release resources. Idempotent. */
    @Override
    void close();
}
