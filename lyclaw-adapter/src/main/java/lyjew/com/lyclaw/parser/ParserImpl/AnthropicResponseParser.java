package lyjew.com.lyclaw.parser.ParserImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.dto.response.AnthropicResponse;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.parser.ResponseParser;
import org.springframework.stereotype.Component;

/**
 * Anthropic 格式响应的解析器实现。
 *
 * <p>负责识别和解析符合 Anthropic Messages API 格式的 JSON 响应。
 * 通过检查 JSON 根节点中的 {@code "type": "message"} 字段来判断
 * 当前响应是否属于 Anthropic 格式。</p>
 *
 * <p>该解析器对应的格式标识为 {@code "anthropic"}。</p>
 *
 * @see AnthropicResponse
 * @see ResponseParser
 */
@Component
public class AnthropicResponseParser implements ResponseParser {

    /** 该解析器对应的 API 格式标识 */
    private static final String FORMAT = "anthropic";
    private final ObjectMapper objectMapper;

    public AnthropicResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 判断原始 JSON 是否为 Anthropic Messages API 格式。
     *
     * <p>通过 Jackson 解析 JSON 树，检查根节点的 {@code type} 字段
     * 是否等于 "message"。</p>
     *
     * @param rawJson 原始响应 JSON 字符串
     * @return true 表示是 Anthropic 格式
     */
    @Override
    public boolean canParse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode typeNode = root.get("type");
            // 特征字段检查：Anthropic 响应的顶层 type 为 "message"
            return typeNode != null && "message".equals(typeNode.asText());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 将原始 JSON 解析为指定类型的对象。
     *
     * @param rawJson 原始响应 JSON 字符串
     * @param clazz   目标类型
     * @param <T>     泛型类型
     * @return 解析后的对象
     * @throws ModelException 解析失败时抛出
     */
    @Override
    public <T> T parse(String rawJson, Class<T> clazz) {
        try {
            return objectMapper.readValue(rawJson, clazz);
        } catch (Exception e) {
            throw ModelException.of(ErrorCode.MODEL_RESPONSE_PARSE_ERROR,
                    "Anthropic格式解析失败: " + e.getMessage());
        }
    }

    /**
     * 获取解析器格式标识。
     *
     * @return 固定返回 "anthropic"
     */
    @Override
    public String getFormat() {
        return FORMAT;
    }

    /**
     * 将原始 JSON 直接解析为 {@link AnthropicResponse} 对象。
     *
     * <p>这是 {@link #parse(String, Class)} 的便捷重载，
     * 目标类型固定为 {@code AnthropicResponse.class}。</p>
     *
     * @param rawJson 原始响应 JSON 字符串
     * @return 解析后的 AnthropicResponse 对象
     */
    public AnthropicResponse parse(String rawJson) {
        return parse(rawJson, AnthropicResponse.class);
    }
}
