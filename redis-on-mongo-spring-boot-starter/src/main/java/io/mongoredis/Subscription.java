package io.mongoredis;

/**
 * Handle to an active pub/sub subscription. Closing it stops the backing
 * Change Stream and releases the worker thread.
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
