package lyjew.com.lyclaw.parser.ParserImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.dto.response.OpenAIResponse;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.parser.ResponseParser;
import org.springframework.stereotype.Component;

@Component
public class OpenAIResponseParser implements ResponseParser {

    private static final String FORMAT = "openai";
    private final ObjectMapper objectMapper;

    public OpenAIResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean canParse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode objectNode = root.get("object");
            return objectNode != null && "chat.completion".equals(objectNode.asText());
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
                    "OpenAI格式解析失败: " + e.getMessage());
        }
    }

    @Override
    public String getFormat() {
        return FORMAT;
    }

    public OpenAIResponse parse(String rawJson) {
        return parse(rawJson, OpenAIResponse.class);
    }
}
