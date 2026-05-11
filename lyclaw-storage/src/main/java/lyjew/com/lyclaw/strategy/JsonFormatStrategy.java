package lyjew.com.lyclaw.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON格式序列化/反序列化策略。
 *
 * <p>实现{@link FormatStrategy}泛型接口，使用Jackson {@link ObjectMapper}
 * 将任意Java对象序列化为JSON字符串或从JSON字符串反序列化为Java对象。
 * 文件后缀为".json"。</p>
 *
 * <p>适用于需要结构化存储的场景（如配置对象、复杂DTO等），
 * 相比纯文本格式，JSON保留了完整的对象结构和类型信息。</p>
 *
 * @param <T> 需要序列化/反序列化的实体类型
 * @author lyjew
 */
@Slf4j
public class JsonFormatStrategy<T> implements FormatStrategy<T> {

    /** Jackson对象映射器，用于JSON的序列化与反序列化 */
    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper Jackson ObjectMapper实例
     */
    public JsonFormatStrategy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将实体对象序列化为JSON字符串。
     *
     * @param entity 待序列化的实体对象
     * @return JSON格式的字符串
     * @throws RuntimeException 序列化失败时抛出
     */
    @Override
    public String serialize(T entity) {
        try {
            return objectMapper.writeValueAsString(entity);
        } catch (Exception e) {
            throw new RuntimeException("JSON序列化失败", e);
        }
    }

    /**
     * 将JSON字符串反序列化为指定类型的实体对象。
     *
     * @param content JSON格式字符串
     * @param clazz   目标实体类型
     * @return 反序列化后的实体对象，包含完整的对象结构和类型信息
     * @throws RuntimeException 反序列化失败时抛出，同时记录错误日志
     */
    @Override
    public T deserialize(String content, Class<T> clazz) {
        try {
            return objectMapper.readValue(content, clazz);
        } catch (Exception e) {
            log.error("JSON反序列化失败 class={} content={}", clazz.getSimpleName(), content.substring(0, Math.min(200, content.length())), e);
            throw new RuntimeException("JSON反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 返回JSON策略对应的文件后缀名。
     *
     * @return "json"
     */
    @Override
    public String suffix() {
        return "json";
    }
}