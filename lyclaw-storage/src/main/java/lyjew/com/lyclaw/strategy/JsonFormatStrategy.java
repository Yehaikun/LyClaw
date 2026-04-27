package lyjew.com.lyclaw.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonFormatStrategy<T> implements FormatStrategy<T> {

    private final ObjectMapper objectMapper;

    public JsonFormatStrategy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(T entity) {
        try {
            return objectMapper.writeValueAsString(entity);
        } catch (Exception e) {
            throw new RuntimeException("JSON序列化失败", e);
        }
    }

    @Override
    public T deserialize(String content, Class<T> clazz) {
        try {
            return objectMapper.readValue(content, clazz);
        } catch (Exception e) {
            throw new RuntimeException("JSON反序列化失败", e);
        }
    }

    @Override
    public String suffix() {
        return "json";
    }
}