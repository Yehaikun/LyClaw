package lyjew.com.lyclaw.parser.ParserImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.dto.response.AnthropicResponse;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.parser.ResponseParser;
import org.springframework.stereotype.Component;

/**
 * Anthropic 格式响应解析器
 *
 * 负责识别并解析 Anthropic 格式的响应（MiniMax 和 DeepSeek Anthropic 格式）。
 *
 * 判断依据：响应 JSON 中包含 "type": "message" 字段（Anthropic 格式的特征）。
 */
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
            // Anthropic 格式的特征：顶层有 type 字段且值为 "message"
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

    // ==================== 便捷方法 ====================

    /**
     * 直接解析为 AnthropicResponse（无需传 Class）
     */
    public AnthropicResponse parse(String rawJson) {
        return parse(rawJson, AnthropicResponse.class);
    }
}