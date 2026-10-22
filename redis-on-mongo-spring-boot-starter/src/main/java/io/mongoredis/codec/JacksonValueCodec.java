package io.mongoredis.codec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Date;

/**
 * Default {@link ValueCodec}. Primitive-like values are stored as-is; any other
 * object is serialized to a JSON string via Jackson.
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
    public Object encode(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Date) {
            return value;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to encode value of type " + value.getClass(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T decode(Object stored, Class<T> type) {
        if (stored == null) {
            return null;
        }
        if (type.isInstance(stored)) {
            return (T) stored;
        }
        try {
            if (stored instanceof String) {
                return mapper.readValue((String) stored, type);
            }
            return mapper.convertValue(stored, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decode stored value into " + type, e);
        }
    }
}
