package lyjew.com.lyclaw.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AI 智能体（Agent）声明注解，用于将一个类标记为 LyClaw 框架中的自主 AI 智能体组件。
 *
 * <p>在 LyClaw 的智能体架构中，Agent 是具有独立决策能力、可自主执行多步任务的高级
 * AI 组件。与单一的 Tool 不同，Agent 通常包含更复杂的内部逻辑，如多轮对话管理、
 * 工具编排、子任务分解和结果综合等能力。被 {@code @Agent} 注解标记的类通过
 * {@link org.springframework.stereotype.Component} 元注解自动被 Spring 容器发现并
 * 注册为 Bean，框架的 AgentRegistry 负责管理所有已注册的 Agent 实例。
 *
 * <p>Agent 的生命周期由框架管理：启动时自动注册，运行时通过名称查找和调度，
 * 支持在对话流程中作为子任务执行器被调用，也支持独立的 Agent-to-Agent 通信。
 *
 * <p>核心属性说明：
 * <ul>
 *   <li><b>name</b>：Agent 的名称，用于在框架中唯一标识和查找该 Agent。建议使用
 *       简短有意义的英文名称，如 "code-reviewer"、"data-analyst"</li>
 *   <li><b>description</b>：Agent 的功能描述，用于运维面板展示和自动生成
 *       AI 可理解的 Agent 能力说明，帮助路由决策选择最合适的 Agent</li>
 *   <li><b>version</b>：Agent 的语义化版本号（SemVer），用于追踪 Agent 的迭代历史
 *       和兼容性管理</li>
 * </ul>
 *
 * @see lyjew.com.lyclaw.annotation.tool.Tool
 * @see lyjew.com.lyclaw.annotation.PipelineStage
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Agent {

    /**
     * Agent 的名称，用于在框架的 AgentRegistry 中唯一标识该 Agent。
     *
     * <p>该名称将作为注册表中的键值，运行时通过此名称查找和调度 Agent。建议使用
     * 简短、描述性强的小写英文标识，如 "code-reviewer"、"data-analyst"。
     *
     * @return Agent 的名称字符串，默认为空字符串（使用类名推断）
     */
    String name() default "";

    /**
     * Agent 的功能描述，说明该 Agent 的用途、能力和适用场景。
     *
     * <p>该描述将展示在运维面板和 Actuator 端点中，也可能被注入到 AI 模型的
     * 上下文中作为 Agent 选择决策的参考信息。
     *
     * @return Agent 的功能描述字符串，默认为空字符串
     */
    String description() default "";

    /**
     * Agent 的语义化版本号，遵循 SemVer 规范（主版本.次版本.修订号）。
     *
     * <p>版本号用于追踪 Agent 的功能迭代和接口变更，框架的依赖管理系统可据此
     * 进行兼容性检查和版本冲突检测。
     *
     * @return Agent 的版本号字符串，默认为 "1.0.0"
     */
    String version() default "1.0.0";

    /**
     * 指定该 Agent 使用的模型名称（如 "deepseek-v4-flash"）。
     * 为空时使用系统默认模型。
     */
    String model() default "";

    /**
     * 指定该 Agent 使用的模型提供商（如 "deepseek"、"openai"）。
     * 为空时使用系统默认提供商。
     */
    String provider() default "";

    /**
     * 扩展配置键值对，用于为 Agent 注入框架级配置而不修改注解定义。
     *
     * <p>支持的功能键包括（不限于）：
     * <ul>
     *   <li>{@code planning.enabled} — 是否启用任务规划</li>
     *   <li>{@code planning.strategy} — 规划策略（dag/cot/react/hierarchical）</li>
     *   <li>{@code memory.topK} — 记忆检索数量</li>
     *   <li>{@code tool.dynamicFiltering} — 是否启用动态工具筛选</li>
     *   <li>{@code mcp.servers} — MCP Server 地址列表（逗号分隔）</li>
     *   <li>{@code outputGuard.enabled} — 是否启用输出护栏</li>
     *   <li>{@code communication.protocol} — Agent 通信协议</li>
     *   <li>{@code maxToolRounds} — 最大工具调用轮数</li>
     *   <li>{@code sandbox} — 沙箱级别</li>
     * </ul>
     */
    Extension[] extensions() default {};
}
