package lyjew.com.lyclaw.annotation.chat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明模型适配器支持的具体能力。
 *
 * <p>与 {@link ChatModel} 配合使用，框架根据能力声明自动决定可用功能：
 * <ul>
 *   <li>toolCalling=false → 框架在 Function Calling 场景跳过该模型</li>
 *   <li>streaming=false → 框架自动将 stream() 转为 call() + 单元素 Flux</li>
 *   <li>thinking=true → 框架在 ChatRequest.thinkingEnabled 时传递 thinking 参数</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModelCapability {

    /** 是否支持原生流式 */
    boolean streaming() default true;

    /** 是否支持工具调用（Function Calling） */
    boolean toolCalling() default false;

    /** 是否支持流式工具调用 */
    boolean toolCallStreaming() default false;

    /** 是否支持思考模式 */
    boolean thinking() default false;

    /** 是否支持多模态/图片 */
    boolean vision() default false;

    /** 是否支持 Prompt 缓存优化 */
    boolean promptCaching() default false;

    /** 最大输入 token 数 */
    int maxInputTokens() default 8192;

    /** 最大输出 token 数 */
    int maxOutputTokens() default 4096;
}
