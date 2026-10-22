package io.pgredis.codec;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Default {@link ValueCodec}. Strings are stored verbatim; everything else is
 * serialized to JSON via Jackson.
 */
public class JacksonValueCodec implements ValueCodec {

    private final ObjectMapper mapper;

    public JacksonValueCodec() {
        this(new ObjectMapper());
    }

    public JacksonValueCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String encode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to encode value of type " + value.getClass(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T decode(String stored, Class<T> type) {
        if (stored == null) {
            return null;
        }
        if (type == String.class) {
            return (T) stored;
        }
        try {
            return mapper.readValue(stored, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decode stored value into " + type, e);
        }
    }
}
