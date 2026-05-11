package lyjew.com.lyclaw.strategy;

/**
 * 序列化格式策略接口，定义实体的序列化/反序列化及文件后缀规范。
 *
 * <p>实现类提供特定的数据格式支持（如 JSON、Markdown），
 * 供 {@link lyjew.com.lyclaw.base.BaseStorage} 等组件使用。</p>
 *
 * @param <T> 实体类型
 */
public interface FormatStrategy<T> {

    /** 将实体序列化为字符串。 */
    String serialize(T entity);

    /** 从字符串反序列化为指定类型的实体。 */
    T deserialize(String content, Class<T> clazz);

    /** @return 文件后缀名（不含点号，如 "json"、"md"） */
    String suffix();
}
