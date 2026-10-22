package io.pgredis.codec;

/**
 * Converts application values to/from the text representation stored in the
 * {@code value} column.
 */
public interface ValueCodec {

    /** Encode a value to the string stored in the database. */
    String encode(Object value);

    /** Decode a stored string back into the requested type. */
    <T> T decode(String stored, Class<T> type);
}
