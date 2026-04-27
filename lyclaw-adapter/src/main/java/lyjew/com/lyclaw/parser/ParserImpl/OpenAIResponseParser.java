package lyjew.com.lyclaw.parser.ParserImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.dto.response.OpenAIResponse;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.parser.ResponseParser;
import org.springframework.stereotype.Component;

/**
 * OpenAI 格式响应解析器
 *
 * 负责识别并解析 OpenAI 兼容格式的响应（DeepSeek OpenAI 格式）。
 *
 * 判断依据：响应 JSON 中包含 "object": "chat.completion" 字段（OpenAI 格式的特征）。
 */
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
            // OpenAI 格式的特征：顶层有 object 字段且值为 "chat.completion"
            JsonNode objectNode = root.get("object");
            return objectNode != null
                    && "chat.completion".equals(objectNode.asText());
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

    // ==================== 便捷方法 ====================

    /**
     * 直接解析为 OpenAIResponse（无需传 Class）
     */
    public OpenAIResponse parse(String rawJson) {
        return parse(rawJson, OpenAIResponse.class);
    }
}