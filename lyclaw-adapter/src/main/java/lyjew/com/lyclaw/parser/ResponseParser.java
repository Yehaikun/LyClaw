package lyjew.com.lyclaw.parser;

/**
 * 模型响应解析器接口，定义了将不同大模型厂商返回的原始 JSON 响应
 * 解析为统一业务对象的规范。
 *
 * <p>每个实现类对应一种 API 格式（如 OpenAI、Anthropic），
 * 通过 {@link #canParse(String)} 判断是否能处理给定的响应，
 * 通过 {@link #parse(String, Class)} 执行实际的解析。</p>
 *
 * @see lyjew.com.lyclaw.parser.ParserImpl.OpenAIResponseParser
 * @see lyjew.com.lyclaw.parser.ParserImpl.AnthropicResponseParser
 */
public interface ResponseParser {

    /**
     * 判断当前解析器是否能处理给定的原始 JSON 响应。
     *
     * <p>通常会检查 JSON 中的特征字段（如 OpenAI 的 "object": "chat.completion"
     * 或 Anthropic 的 "type": "message"）来做判断。</p>
     *
     * @param rawJson 模型 API 返回的原始 JSON 字符串
     * @return true 表示该解析器可以处理此格式的响应
     */
    boolean canParse(String rawJson);

    /**
     * 将原始 JSON 字符串解析为指定类型的对象。
     *
     * @param rawJson 模型 API 返回的原始 JSON 字符串
     * @param clazz   目标解析类型
     * @param <T>     泛型类型参数
     * @return 解析后的对象
     * @throws lyjew.com.lyclaw.exception.ModelException 当解析失败时抛出
     */
    <T> T parse(String rawJson, Class<T> clazz);

    /**
     * 获取该解析器对应的 API 格式标识。
     *
     * @return 格式标识字符串，如 "openai" 或 "anthropic"
     */
    String getFormat();
}
