package lyjew.com.lyclaw.reflect.registry;

import java.lang.annotation.*;

/**
 * 在 @Agent 接口上声明反思拓扑配置。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReflectionConfig {
    /** 预置拓扑模板名，默认 "passthrough" */
    String topology() default "passthrough";
    /** 最大重试次数 */
    int maxRetries() default 3;
    /** 质量阈值（0.0~1.0） */
    double threshold() default 0.7;
    /** Evaluator 实现名 */
    String evaluator() default "llmJudgeEvaluator";
    /** 是否启用跨会话记忆 */
    boolean memoryEnabled() default false;
    /** 记忆存储类型 */
    String memoryType() default "inMemory";
    /** 自定义评估 prompt */
    String customPrompt() default "";
}
