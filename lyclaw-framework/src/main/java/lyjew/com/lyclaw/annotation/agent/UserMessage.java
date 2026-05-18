package lyjew.com.lyclaw.annotation.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 定义 Agent 方法的用户消息。
 *
 * <p>可以标注在方法上或参数上，有两种使用模式：
 *
 * <h3>模式一：参数注入（标注在参数上）</h3>
 * <pre>
 * String chat(&#064;UserMessage String message);
 * </pre>
 * 被标注的参数值直接作为 user message。
 *
 * <h3>模式二：模板模式（标注在方法上）</h3>
 * <pre>
 * &#064;UserMessage("翻译以下文本从 {{source}} 到 {{target}}：{{text}}")
 * String translate(&#064;V("text") String text, &#064;V("source") String src, &#064;V("target") String tgt);
 * </pre>
 * 方法上的模板字符串中的 {@code {{varname}}} 占位符由 {@code @V} 注解的参数值替换。
 *
 * <p>如果方法上同时有 &#064;UserMessage 模板和 &#064;UserMessage 参数，方法模板优先。
 */
@Target({ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UserMessage {

    /**
     * 用户消息模板，支持 {@code {{varname}}} 占位符。
     * 当标注在参数上时，value 为空表示直接使用该参数的值。
     */
    String value() default "";
}
