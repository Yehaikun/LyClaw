package lyjew.com.lyclaw.annotation.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import lyjew.com.lyclaw.storage.MemoryLayer;

/**
 * 声明存储后端适用于记忆层（Memory Store）。
 *
 * <p>记忆层数据跨 Session、多年保留，需要语义检索能力。
 * 典型后端为 PostgreSQL+pgvector、SQLite+vec 或 Milvus。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MemoryStore {

    /** 此层级的优先级覆盖 */
    int layerPriority() default 0;

    /** 是否此层级的默认后端 */
    boolean layerDefault() default false;

    /** 支持的记忆层级（默认全支持） */
    MemoryLayer[] supportedLayers() default {
        MemoryLayer.SENSORY,
        MemoryLayer.SHORT_TERM,
        MemoryLayer.LONG_TERM,
        MemoryLayer.ENTITY
    };
}
