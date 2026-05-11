package lyjew.com.lyclaw.annotation.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明存储命名空间，隔离不同业务域的数据。
 *
 * <p>框架在构造存储 key 时自动拼接 namespace 前缀。
 * 用于标注类（整个存储类操作同一命名空间）或字段（特定字段使用不同命名空间）。
 */
@Target({ElementType.TYPE, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface StorageNamespace {

    /** 命名空间名称，如 "sessions"、"configs"、"memory" */
    String value();

    /** 命名空间描述 */
    String description() default "";
}
