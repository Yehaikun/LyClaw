package lyjew.com.lyclaw.annotation.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明该类是一个存储后端实现。
 *
 * <p>标注了此注解的类会被 {@code StorageBackendPostProcessor} 自动发现和注册。
 * 框架在启动时扫描所有 @StorageBackend Bean，构建 name→StorageBackend 的注册表，
 * 运行时通过名称查找和使用。</p>
 *
 * <p>使用示例：
 * <pre>{@code
 * @StorageBackend(name = "sqlite", displayName = "SQLite存储", priority = 100)
 * public class SQLiteBackend implements StorageBackend { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StorageBackend {

    /** 后端全局唯一名称。用于配置引用：lyclaw.storage.stores.entity.backend=sqlite */
    String name();

    /** 后端显示名称，用于 Actuator 端点和运维面板 */
    String displayName() default "";

    /** 后端描述，说明适用场景和依赖 */
    String description() default "";

    /** 语义化版本号 */
    String version() default "1.0.0";

    /** 当多个后端声明支持同一层时，优先级高的优先被选为默认（值越大优先级越高） */
    int priority() default 0;

    /** 是否自动注册到 StorageBackendRegistry。false 时可手动调用 registry.register() */
    boolean autoRegister() default true;
}
