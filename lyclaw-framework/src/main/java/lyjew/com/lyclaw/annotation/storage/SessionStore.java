package lyjew.com.lyclaw.annotation.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明存储后端适用于会话层（Session Store）。
 *
 * <p>会话层数据具有短生命周期（单次会话或数分钟），需要高频读写和低延迟，
 * 典型后端为 Redis 或 InMemory。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SessionStore {

    /** 此层级的优先级覆盖（覆盖 @StorageBackend 全局 priority） */
    int layerPriority() default 0;

    /** 是否此层级的默认后端（同一层只能有一个默认） */
    boolean layerDefault() default false;
}
