package lyjew.com.lyclaw.autoconfigure.binding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class ParameterBinder {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static <T> T bind(String json, Class<T> targetType) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, targetType);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to bind JSON to " + targetType.getName() + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> bindToMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse JSON: " + e.getMessage(), e);
        }
    }
}
