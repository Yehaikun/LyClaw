package lyjew.com.lyclaw.parser.ParserImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.dto.response.OpenAIResponse;
import lyjew.com.lyclaw.enums.ErrorCode;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.parser.ResponseParser;
import org.springframework.stereotype.Component;

/**
 * OpenAI 格式响应的解析器实现。
 *
 * <p>负责识别和解析符合 OpenAI Chat Completion API 格式的 JSON 响应。
 * 通过检查 JSON 根节点中的 {@code "object": "chat.completion"} 字段来判断
 * 当前响应是否属于 OpenAI 格式。</p>
 *
 * <p>该解析器对应的格式标识为 {@code "openai"}。</p>
 *
 * @see OpenAIResponse
 * @see ResponseParser
 */
@Component
public class OpenAIResponseParser implements ResponseParser {

    /** 该解析器对应的 API 格式标识 */
    private static final String FORMAT = "openai";
    private final ObjectMapper objectMapper;

    public OpenAIResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 判断原始 JSON 是否为 OpenAI Chat Completion 格式。
     *
     * <p>通过 Jackson 解析 JSON 树，检查根节点的 {@code object} 字段
     * 是否等于 "chat.completion"。</p>
     *
     * @param rawJson 原始响应 JSON 字符串
     * @return true 表示是 OpenAI 格式
     */
    @Override
    public boolean canParse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode objectNode = root.get("object");
            // 特征字段检查：OpenAI 响应的顶层 object 为 "chat.completion"
            return objectNode != null && "chat.completion".equals(objectNode.asText());
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
                    "OpenAI格式解析失败: " + e.getMessage());
        }
    }

    /**
     * 获取解析器格式标识。
     *
     * @return 固定返回 "openai"
     */
    @Override
    public String getFormat() {
        return FORMAT;
    }

    /**
     * 将原始 JSON 直接解析为 {@link OpenAIResponse} 对象。
     *
     * <p>这是 {@link #parse(String, Class)} 的便捷重载，
     * 目标类型固定为 {@code OpenAIResponse.class}。</p>
     *
     * @param rawJson 原始响应 JSON 字符串
     * @return 解析后的 OpenAIResponse 对象
     */
    public OpenAIResponse parse(String rawJson) {
        return parse(rawJson, OpenAIResponse.class);
    }
}
