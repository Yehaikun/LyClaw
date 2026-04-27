package lyjew.com.lyclaw.parser;

/**
 * 响应解析器接口——责任链模式中的处理器
 *
 * 不同厂商的响应格式不同（Anthropic 格式 vs OpenAI 格式），
 * 每个解析器负责识别并解析一种格式。
 *
 * 设计模式：责任链模式（Chain of Responsibility）
 * 多个解析器按顺序尝试，第一个能处理的解析器负责解析
 */
public interface ResponseParser {

    /**
     * 判断当前解析器能否处理这个原始响应
     *
     * @param rawJson 厂商返回的原始 JSON 字符串
     * @return true 表示这个解析器可以处理
     */
    boolean canParse(String rawJson);

    /**
     * 解析原始响应为厂商特定的响应对象
     *
     * @param rawJson 厂商返回的原始 JSON 字符串
     * @param clazz   要解析成的目标类型
     * @param <T>     响应对象的类型
     * @return 解析后的响应对象
     */
    <T> T parse(String rawJson, Class<T> clazz);

    /**
     * 获取这个解析器对应的格式名称
     *
     * @return 格式名，如 "anthropic"、"openai"
     */
    String getFormat();
}