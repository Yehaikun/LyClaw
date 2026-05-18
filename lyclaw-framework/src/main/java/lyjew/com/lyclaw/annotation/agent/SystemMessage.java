package lyjew.com.lyclaw.annotation.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 定义 Agent 方法的系统提示词（System Prompt）。
 *
 * <p>标注在 Agent 接口方法上，框架在调用该方法时自动将 value 作为 system message
 * 注入到 ChatRequest 中，构建完整的 LLM 对话上下文。
 *
 * <pre>
 * &#064;Agent(name = "assistant")
 * public interface Assistant {
 *     &#064;SystemMessage("You are a helpful assistant with tool access.")
 *     String chat(&#064;UserMessage String message);
 * }
 * </pre>
 *
 * <p>如果方法上同时存在 &#064;SystemMessage 和 Agent 的 description，以方法上的
 * &#064;SystemMessage 为准。如果两者都没有，则不添加 system message。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SystemMessage {

    /** 系统提示词内容，支持 {@code {{varname}}} 模板占位符 */
    String value();
}
