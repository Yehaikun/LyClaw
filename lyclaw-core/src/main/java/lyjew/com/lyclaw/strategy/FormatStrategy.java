package lyjew.com.lyclaw.strategy;

/**
 * 数据格式化策略——决定实体怎么变成字符串，字符串怎么变回实体
 */
public interface FormatStrategy<T> {

    /** 实体 → 字符串 */
    String serialize(T entity);

    /** 字符串 → 实体 */
    T deserialize(String content, Class<T> clazz);

    /** 文件后缀，如 "json" "md" "yaml" */
    String suffix();
}