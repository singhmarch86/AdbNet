package io.mongoredis;

/**
 * Callback invoked for each message delivered on a subscribed channel.
 *
 * <p>Delivery is backed by a MongoDB Change Stream, which requires the
 * MongoDB deployment to be a replica set.
 */
@FunctionalInterface
public interface MessageListener {

    /**
     * @param channel the channel the message was published to
     * @param payload the raw stored payload (see {@link io.mongoredis.codec.ValueCodec})
     */
    void onMessage(String channel, Object payload);
}
