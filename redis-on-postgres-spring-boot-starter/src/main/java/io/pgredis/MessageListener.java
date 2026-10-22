package io.pgredis;

/**
 * Callback for each message delivered on a subscribed channel.
 * Backed by PostgreSQL {@code LISTEN}/{@code NOTIFY}.
 */
@FunctionalInterface
public interface MessageListener {

    /**
     * @param channel the channel the message was published to
     * @param payload the NOTIFY payload (a string; see the 8000-byte limit)
     */
    void onMessage(String channel, String payload);
}
