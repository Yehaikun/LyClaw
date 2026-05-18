package lyjew.com.lyclaw.annotation.agent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将方法参数绑定到 &#064;UserMessage 或 &#064;SystemMessage 模板中的占位符变量。
 *
 * <pre>
 * &#064;UserMessage("Translate {{text}} from {{source}} to {{target}}")
 * String translate(&#064;V("text") String text, &#064;V("source") String src, &#064;V("target") String tgt);
 * </pre>
 *
 * <p>框架执行时将 {@code {{text}}} 替换为 text 参数值，{@code {{source}}} 替换为 src，
 * 依此类推。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface V {

    /** 模板中的占位符变量名（不含花括号），对应 {@code {{name}}} */
    String value();
}
