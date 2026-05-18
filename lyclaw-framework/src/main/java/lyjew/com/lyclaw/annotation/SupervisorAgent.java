package lyjew.com.lyclaw.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 监督者 Agent 注解，用于声明一个可编排多个子 Agent 的监督者。
 *
 * <p>监督者 Agent 负责接收复杂请求，分解为子任务并分派给子 Agent 执行，
 * 然后综合各子 Agent 的结果返回最终响应。支持两种协作模式：
 * <ul>
 *   <li><b>HANDOFF</b> — 将对话直接转交给最合适的子 Agent，监督者不再参与</li>
 *   <li><b>HIERARCHICAL</b> — 监督者分解任务、分派子 Agent、聚合结果</li>
 * </ul>
 *
 * <pre>
 * &#064;SupervisorAgent(name = "orchestrator", subAgents = {"researcher", "coder"},
 *                    mode = SupervisorMode.HIERARCHICAL)
 * public interface OrchestratorAgent {
 *     &#064;SystemMessage("You coordinate sub-agents to solve complex tasks.")
 *     String execute(&#064;UserMessage String task);
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SupervisorAgent {

    String name() default "";

    String description() default "";

    /** 子 Agent 名称列表，监督者可分派任务给这些 Agent */
    String[] subAgents() default {};

    /** 协作模式，默认 HIERARCHICAL（分派+聚合） */
    SupervisorMode mode() default SupervisorMode.HIERARCHICAL;

    /** 每个子任务的最大重试次数（Reflexion 循环） */
    int maxRetries() default 2;

    /** Reflexion 质量阈值（0.0-1.0），低于此值触发重试 */
    double qualityThreshold() default 0.6;
}
