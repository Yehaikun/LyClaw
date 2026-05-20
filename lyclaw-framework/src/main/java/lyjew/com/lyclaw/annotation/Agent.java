package lyjew.com.lyclaw.annotation;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

/**
 * AI Agent 声明注解 —— 对标 OpenClaw AgentConfig 的完整字段集合。
 *
 * 字段解析优先级：Agent 级别 > 全局默认值(lyclaw.agent.defaults.*) > 系统内置默认值。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Agent {

    // ── 身份标识 ──────────────────────────────────────────────
    /** Agent 唯一标识符。为空时从类简单名称派生。 */
    String id() default "";

    /** 此 Agent 是否为默认 Agent。 */
    boolean defaultAgent() default false;

    /** 人类可读的显示名称。 */
    String name() default "";

    /** UI 中显示的描述信息，用于 Agent 选择路由。 */
    String description() default "";

    /** 语义化版本（SemVer）。 */
    String version() default "1.0.0";

    // ── 工作区 ─────────────────────────────────────────────────
    /** 此 Agent 的工作区根目录。为空则使用全局工作区。 */
    String workspace() default "";

    /** 工作区下 Agent 专属子目录。为空则使用 agent id。 */
    String agentDir() default "";

    // ── 系统提示词 ──────────────────────────────────────────
    /** 覆盖从引导文件加载的系统提示词。 */
    String systemPromptOverride() default "";

    // ── 模型 ──────────────────────────────────────────────────
    /** 模型名称（如 "deepseek-v4-flash"）。为空 = 使用默认值。 */
    String model() default "";

    /** 提供商键值（如 "deepseek"、"openai"）。为空 = 使用默认值。 */
    String provider() default "";

    /** 有序的备用模型键值列表，主模型失败时按序尝试。 */
    String[] fallbacks() default {};

    // ── 技能 ─────────────────────────────────────────────────
    /** 附加到此 Agent 的技能标识符列表。 */
    String[] skills() default {};

    // ── 思考 / 详细度 / 推理 ─────────────────────────────
    /** 默认思考级别: off, minimal, low, medium, high, xhigh, adaptive, max。 */
    String thinkingDefault() default "";

    /** 默认详细度级别。 */
    String verboseDefault() default "";

    /** 默认推理级别。 */
    String reasoningDefault() default "";

    /** 快速模式：为 true 时跳过昂贵的预处理步骤。 */
    boolean fastModeDefault() default false;

    // ── 上下文 ─────────────────────────────────────────────────
    /** 为此 Agent 预留的上下文窗口 Token 数。0 = 使用全局默认值。 */
    int contextTokens() default 0;

    /** 从单个引导文件中加载的最大字符数。0 = 使用默认值。 */
    int bootstrapMaxChars() default 0;

    /** 所有引导文件合计的最大字符数。0 = 使用默认值。 */
    int bootstrapTotalMaxChars() default 0;

    /** 何时注入引导内容: always, continuation-skip, never。 */
    String contextInjection() default "always";

    // ── 子 Agent 委托 ────────────────────────────────────
    /** 委托模式: suggest（建议，用户确认）或 prefer（优先委托）。 */
    String delegationMode() default "suggest";

    /** 允许委托的 Agent ID 白名单。为空 = 不限制。 */
    String[] allowAgents() default {};

    /** 生成子 Agent 的最大嵌套深度。0 = 使用默认值。 */
    int maxSpawnDepth() default 0;

    /** 单层中最多可生成的子 Agent 数量。0 = 使用默认值。 */
    int maxChildrenPerAgent() default 0;

    // ── 沙箱 ────────────────────────────────────────────────
    /** 沙箱模式: none, docker, podman。 */
    String sandbox() default "";

    // ── 扩展（向后兼容的逃生舱口） ──────────────────────
    /** 供插件使用的任意键值对。优先使用上方的类型化字段。 */
    Extension[] extensions() default {};
}
