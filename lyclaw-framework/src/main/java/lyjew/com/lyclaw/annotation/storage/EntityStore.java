package lyjew.com.lyclaw.annotation.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明存储后端适用于实体层（Entity Store）。
 *
 * <p>实体层数据需要长期持久和版本化管理，典型后端为 PostgreSQL、SQLite 或 File。
 * 支持事务声明、配置归档、Session 存档等场景。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityStore {

    /** 此层级的优先级覆盖 */
    int layerPriority() default 0;

    /** 是否此层级的默认后端 */
    boolean layerDefault() default false;

    /** 是否支持事务（如 PostgreSQL 支持，FileBackend 不支持） */
    boolean supportsTransaction() default false;
}
