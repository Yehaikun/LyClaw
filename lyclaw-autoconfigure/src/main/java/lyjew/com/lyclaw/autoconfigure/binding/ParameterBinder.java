package lyjew.com.lyclaw.autoconfigure.binding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * JSON参数绑定工具类。
 *
 * <p>提供将JSON字符串绑定（反序列化）为Java对象或Map的静态方法。
 * 内部使用Jackson {@link ObjectMapper}完成JSON解析，对外提供简洁的API。
 * 主要用于自动配置场景中将配置参数、请求体等JSON数据转换为类型安全的Java对象。</p>
 *
 * <p>典型使用场景：
 * <ul>
 *   <li>将扩展配置JSON字符串绑定为特定的配置POJO</li>
 *   <li>将工具参数JSON转换为通用的Map结构</li>
 * </ul>
 * </p>
 *
 * @author lyjew
 */
public class ParameterBinder {
    /** 共享的Jackson ObjectMapper实例，用于JSON解析 */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 将JSON字符串绑定为指定类型的Java对象。
     *
     * @param <T>        目标类型
     * @param json       JSON格式字符串
     * @param targetType 目标类型的Class对象
     * @return 绑定后的对象实例，JSON为null或空时返回null
     * @throws IllegalArgumentException JSON格式不正确或类型不匹配时抛出
     */
    public static <T> T bind(String json, Class<T> targetType) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, targetType);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to bind JSON to " + targetType.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 将JSON字符串绑定为Map结构。
     *
     * <p>适用于键值对不确定的动态配置场景，返回的Map可以灵活访问任意字段。</p>
     *
     * @param json JSON格式字符串
     * @return 解析后的Map对象（键为String，值为Object），JSON为null或空时返回空Map
     * @throws IllegalArgumentException JSON格式不正确时抛出
     */
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
