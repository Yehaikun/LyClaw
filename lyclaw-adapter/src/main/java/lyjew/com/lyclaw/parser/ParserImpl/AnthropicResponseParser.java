package lyjew.com.lyclaw.parser.ParserImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.dto.response.AnthropicResponse;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.parser.ResponseParser;
import org.springframework.stereotype.Component;

@Component
public class AnthropicResponseParser implements ResponseParser {

    private static final String FORMAT = "anthropic";
    private final ObjectMapper objectMapper;

    public AnthropicResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean canParse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode typeNode = root.get("type");
            return typeNode != null && "message".equals(typeNode.asText());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public <T> T parse(String rawJson, Class<T> clazz) {
        try {
            return objectMapper.readValue(rawJson, clazz);
        } catch (Exception e) {
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                    "Anthropic格式解析失败: " + e.getMessage());
        }
    }

    @Override
    public String getFormat() {
        return FORMAT;
    }

    public AnthropicResponse parse(String rawJson) {
        return parse(rawJson, AnthropicResponse.class);
    }
}
