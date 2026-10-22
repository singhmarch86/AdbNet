package io.mongoredis.codec;

/**
 * Converts application values to/from a BSON-storable representation.
 *
 * <p>Implementations must round-trip: {@code decode(encode(v), v.getClass())}
 * should be equal to {@code v}.
 */
public interface ValueCodec {

    /**
     * Encode a value into something the MongoDB driver can store
     * (a primitive wrapper, String, Date, or driver Document).
     */
    Object encode(Object value);

    /**
     * Decode a stored value back into the requested type.
     */
    <T> T decode(Object stored, Class<T> type);
}
