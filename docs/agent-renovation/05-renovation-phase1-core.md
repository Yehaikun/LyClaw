# 第一阶段：Agent核心增强 — 改造方案

> **目标**: 使LyClaw的Agent配置、运行时和Hook系统达到与OpenClaw同等的水平。
> **状态**: 草案
> **依赖**: 无（此为基础设施阶段）

---

## 概述

LyClaw 是一个基于 Java/Spring Boot 的多Agent框架。其Agent系统目前具有：

| 组件 | 当前状态 | 目标 |
|---|---|---|
| `@Agent` 注解 | 6个基本字段（name, description, version, model, provider, extensions） | 约30个字段，达到完全OpenClaw对等水平 |
| `AgentConfig` | 扁平POJO，包含4个核心字段 + extensions Map | 4层层级结构（默认值 / 注解 / yaml / 运行时） |
| `AgentConfigResolver` | 基于优先级的来源合并 | 深度合并解析器，生成ResolvedAgentConfig |
| `AgentContext` | 扁平POJO，约12个字段 | 丰富的上下文，约25个字段 + 快照/恢复 |
| `AgentHook` SPI | 5个方法 + getOrder() | 36个方法的生命周期SPI |
| `AgentInvocationHandler` | JDK动态代理，5个Hook分发 | 完整的Hook生命周期分发 |
| `AgentProxyFactory` | 简单构造函数 + create(Class) | 配置感知工厂，支持运行时类型 |
| Pipeline | 6阶段SSE流式处理 | 相同阶段，增强Hook事件 |
| 运行时模式 | 仅EMBEDDED（ReAct） | EMBEDDED + ACP双模式 |

---

## 1.1 AgentConfig系统重构

### 1.1.1 问题

当前 `@Agent` 注解仅携带6个字段。每个Agent的配置（如thinking级别、沙箱设置、子Agent委托、上下文注入行为、引导限制等）都塞在不透明的 `Extension[]` 键值对中。这使得配置无类型、不可发现且容易出错。同时也没有"全局默认值"的概念供Agent继承。

### 1.1.2 设计：扩展的 `@Agent` 注解

```java
package lyjew.com.lyclaw.annotation;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

/**
 * AI Agent声明注解 — 扩展至完全OpenClaw对等水平。
 *
 * <p>字段解析优先级：Agent级别 > 全局默认值
 * ({@code lyclaw.agent.defaults.*}) > 系统内置默认值。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Agent {

    // ── 身份标识 ──────────────────────────────────────────────
    /** Agent唯一标识符。为空时，从类简单名称（小驼峰）派生。 */
    String id() default "";

    /** 此Agent是否为默认Agent（未指定具体Agent时使用）。 */
    boolean defaultAgent() default false;          // was: (missing)

    /** 人类可读的显示名称。为空时，从id派生。 */
    String name() default "";

    /** 在UI中显示的描述信息，并用于Agent选择路由。 */
    String description() default "";

    /** 语义化版本字符串（SemVer）。 */
    String version() default "1.0.0";

    // ── 工作区 ─────────────────────────────────────────────
    /** 此Agent的工作区根目录。为空表示使用全局工作区。 */
    String workspace() default "";

    /** 工作区下Agent专属子目录。为空表示使用Agent id。 */
    String agentDir() default "";

    // ── 系统提示词覆盖 ────────────────────────────────
    /** 覆盖原本从AGENTS.md等文件引导加载的系统提示词。 */
    String systemPromptOverride() default "";

    // ── 模型 ──────────────────────────────────────────────────
    /** 模型名称（如 "deepseek-v4-flash"）。为空 = 使用默认值。 */
    String model() default "";

    /** 提供商键值（如 "deepseek", "openai"）。为空 = 使用默认值。 */
    String provider() default "";

    /** 有序的备用模型键值列表，主模型失败时按顺序尝试。 */
    String[] fallbacks() default {};

    // ── 技能 ─────────────────────────────────────────────────
    /** 附加到此Agent的技能标识符（如 "web-search", "code-interpreter"）。 */
    String[] skills() default {};

    // ── 思考 / 详细度 / 推理 ─────────────────────────
    /**
     * 默认思考级别。
     * 有效值: off, minimal, low, medium, high, xhigh, adaptive, max。
     * 为空表示使用全局默认值。
     */
    String thinkingDefault() default "";

    /** 默认详细度级别。为空 = 使用全局默认值。 */
    String verboseDefault() default "";

    /** 默认推理级别。为空 = 使用全局默认值。 */
    String reasoningDefault() default "";

    /** 快速模式：为true时跳过昂贵的预处理步骤。 */
    boolean fastModeDefault() default false;

    // ── 上下文限制 ─────────────────────────────────────────
    /** 为此Agent预留的最大上下文窗口Token数。0 = 使用全局默认值。 */
    int contextTokens() default 0;

    /** 从单个引导文件（如AGENTS.md）中加载的最大字符数。 */
    int bootstrapMaxChars() default 20000;

    /** 所有引导文件合计的最大字符数。 */
    int bootstrapTotalMaxChars() default 150000;

    /**
     * 何时将AGENTS.md / CLAUDE.md内容注入系统提示词。
     * 有效值: always, continuation-skip, never。
     */
    String contextInjection() default "always";

    // ── 子Agent委托 ────────────────────────────────────
    /**
     * 子Agent生成的委托模式。
     *   suggest — Agent建议子Agent委托，用户确认
     *   prefer  — Agent倾向委托，减少用户干预
     */
    String delegationMode() default "suggest";

    /** 此Agent允许生成的Agent id白名单。为空 = 不限制。 */
    String[] allowAgents() default {};

    /** 生成的子Agent最大嵌套深度。 */
    int maxSpawnDepth() default 1;

    /** 此Agent在单层中最多可生成的子Agent数量。 */
    int maxChildrenPerAgent() default 5;

    // ── 沙箱 ────────────────────────────────────────────────
    /**
     * 沙箱模式: none, docker, podman。
     * 为空 = 使用全局默认值。
     */
    String sandbox() default "";

    // ── 扩展（向后兼容的逃生舱口） ──────────
    /**
     * 供框架插件使用的任意键值对。
     * 优先使用上方的类型化字段；仅当插件特定配置没有类型化等价字段时使用扩展。
     */
    Extension[] extensions() default {};
}
```

### 1.1.3 AgentDefaultsConfig（全局默认值）

此类绑定到 `application.yml` 中的 `lyclaw.agent.defaults.*`，为每个Agent在其注解级别字段为空时提供回退层。

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import java.util.List;
import java.util.Map;

/**
 * 全局Agent默认值，绑定自 {@code lyclaw.agent.defaults.*}。
 *
 * <p>此类中的每个字段在 @Agent 中都有Agent级别的覆盖。
 * 解析顺序: agent注解 > lyclaw.agent.defaults > 硬编码系统默认值。
 */
@ConfigurationProperties(prefix = "lyclaw.agent.defaults")
public class AgentDefaultsConfig {

    // ── 模型默认值 ─────────────────────────────────────────
    /** 默认模型名称（如 "deepseek-v4-flash"）。 */
    private String model;                    // 系统默认值: "deepseek-v4-flash"

    /** 默认提供商键值。 */
    private String provider;                 // 系统默认值: "deepseek"

    /** 默认有序备用模型键值列表。 */
    private List<String> fallbacks = List.of();

    // ── 思考 / 详细度 / 推理 ─────────────────────────
    /** 默认思考级别: off|minimal|low|medium|high|xhigh|adaptive|max。 */
    private String thinkingDefault;          // 系统默认值: "off"

    /** 默认详细度级别。 */
    private String verboseDefault;           // 系统默认值: ""

    /** 默认推理级别。 */
    private String reasoningDefault;         // 系统默认值: ""

    /** 是否默认开启快速模式。 */
    private boolean fastModeDefault;         // 系统默认值: false

    // ── 上下文 ────────────────────────────────────────────────
    /** 何时注入引导内容: always|continuation-skip|never。 */
    private String contextInjection = "always";

    /** 每个引导文件的最大字符数。 */
    private int bootstrapMaxChars = 20000;

    /** 所有引导文件合计的最大字符数。 */
    private int bootstrapTotalMaxChars = 150000;

    /** 预留的上下文窗口Token数。 */
    private int contextTokens = 0;

    // ── 技能 ─────────────────────────────────────────────────
    /** 附加到所有Agent的默认技能。 */
    private List<String> skills = List.of();

    // ── 沙箱 ────────────────────────────────────────────────
    /** 默认沙箱模式: none|docker|podman。 */
    private String sandbox = "none";

    // ── 子Agent（委托默认值） ────────────────────────
    @NestedConfigurationProperty
    private SubagentDefaults subagents = new SubagentDefaults();

    // ── 心跳检测 ──────────────────────────────────────────────
    @NestedConfigurationProperty
    private HeartbeatDefaults heartbeat = new HeartbeatDefaults();

    // ── 运行重试 ────────────────────────────────────────────
    @NestedConfigurationProperty
    private RunRetryDefaults runRetries = new RunRetryDefaults();

    // ── 上下文限制（工具输出裁剪） ──────────────────
    @NestedConfigurationProperty
    private ContextLimitsDefaults contextLimits = new ContextLimitsDefaults();

    // ── 工作区 ──────────────────────────────────────────────
    /** 默认工作区目录。 */
    private String workspace;

    // ===== Getters / Setters =====

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public List<String> getFallbacks() { return fallbacks; }
    public void setFallbacks(List<String> fallbacks) { this.fallbacks = fallbacks; }

    public String getThinkingDefault() { return thinkingDefault; }
    public void setThinkingDefault(String thinkingDefault) { this.thinkingDefault = thinkingDefault; }

    public String getVerboseDefault() { return verboseDefault; }
    public void setVerboseDefault(String verboseDefault) { this.verboseDefault = verboseDefault; }

    public String getReasoningDefault() { return reasoningDefault; }
    public void setReasoningDefault(String reasoningDefault) { this.reasoningDefault = reasoningDefault; }

    public boolean isFastModeDefault() { return fastModeDefault; }
    public void setFastModeDefault(boolean fastModeDefault) { this.fastModeDefault = fastModeDefault; }

    public String getContextInjection() { return contextInjection; }
    public void setContextInjection(String contextInjection) { this.contextInjection = contextInjection; }

    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public void setBootstrapMaxChars(int bootstrapMaxChars) { this.bootstrapMaxChars = bootstrapMaxChars; }

    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public void setBootstrapTotalMaxChars(int bootstrapTotalMaxChars) { this.bootstrapTotalMaxChars = bootstrapTotalMaxChars; }

    public int getContextTokens() { return contextTokens; }
    public void setContextTokens(int contextTokens) { this.contextTokens = contextTokens; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public String getSandbox() { return sandbox; }
    public void setSandbox(String sandbox) { this.sandbox = sandbox; }

    public SubagentDefaults getSubagents() { return subagents; }
    public void setSubagents(SubagentDefaults subagents) { this.subagents = subagents; }

    public HeartbeatDefaults getHeartbeat() { return heartbeat; }
    public void setHeartbeat(HeartbeatDefaults heartbeat) { this.heartbeat = heartbeat; }

    public RunRetryDefaults getRunRetries() { return runRetries; }
    public void setRunRetries(RunRetryDefaults runRetries) { this.runRetries = runRetries; }

    public ContextLimitsDefaults getContextLimits() { return contextLimits; }
    public void setContextLimits(ContextLimitsDefaults contextLimits) { this.contextLimits = contextLimits; }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }

    // ===== 嵌套配置类 =====

    /** 子Agent委托默认值。 */
    public static class SubagentDefaults {
        /** 默认委托模式: suggest|prefer。 */
        private String delegationMode = "suggest";

        /** Agent id白名单。为空 = 全部允许。 */
        private List<String> allowAgents = List.of();

        /** 默认最大生成深度。 */
        private int maxSpawnDepth = 1;

        /** 默认每个Agent最大子Agent数。 */
        private int maxChildrenPerAgent = 5;

        // 为简洁省略getter/setter
        public String getDelegationMode() { return delegationMode; }
        public void setDelegationMode(String m) { this.delegationMode = m; }
        public List<String> getAllowAgents() { return allowAgents; }
        public void setAllowAgents(List<String> a) { this.allowAgents = a; }
        public int getMaxSpawnDepth() { return maxSpawnDepth; }
        public void setMaxSpawnDepth(int d) { this.maxSpawnDepth = d; }
        public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
        public void setMaxChildrenPerAgent(int c) { this.maxChildrenPerAgent = c; }
    }

    /** 心跳检测配置。 */
    public static class HeartbeatDefaults {
        /** 是否启用心跳检测（周期性的"你仍然存活"提示）。 */
        private boolean enabled = false;

        /** 心跳检测间隔秒数。 */
        private long intervalSeconds = 60;

        /** 触发心跳前的最大空闲秒数。 */
        private long maxIdleSeconds = 300;

        // getters/setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public long getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(long s) { this.intervalSeconds = s; }
        public long getMaxIdleSeconds() { return maxIdleSeconds; }
        public void setMaxIdleSeconds(long s) { this.maxIdleSeconds = s; }
    }

    /** 运行重试配置。 */
    public static class RunRetryDefaults {
        /** 模型失败时的最大重试次数。 */
        private int maxAttempts = 3;

        /** 重试之间的基础延迟毫秒数。 */
        private long baseDelayMs = 1000;

        /** 退避策略: fixed|exponential。 */
        private String backoff = "exponential";

        // getters/setters
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int n) { this.maxAttempts = n; }
        public long getBaseDelayMs() { return baseDelayMs; }
        public void setBaseDelayMs(long d) { this.baseDelayMs = d; }
        public String getBackoff() { return backoff; }
        public void setBackoff(String b) { this.backoff = b; }
    }

    /** 上下文限制（工具输出裁剪 / 内存限制）。 */
    public static class ContextLimitsDefaults {
        /** 从内存检索中包括的最大字符数。 */
        private int memoryGetMaxChars = 50000;

        /** 单个工具结果包含在上下文中的最大字符数。 */
        private int toolResultMaxChars = 80000;

        /** 所有工具结果合计的最大字符数。 */
        private int toolResultTotalMaxChars = 200000;

        // getters/setters
        public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
        public void setMemoryGetMaxChars(int c) { this.memoryGetMaxChars = c; }
        public int getToolResultMaxChars() { return toolResultMaxChars; }
        public void setToolResultMaxChars(int c) { this.toolResultMaxChars = c; }
        public int getToolResultTotalMaxChars() { return toolResultTotalMaxChars; }
        public void setToolResultTotalMaxChars(int c) { this.toolResultTotalMaxChars = c; }
    }
}
```

### 1.1.4 AgentSystemDefaults（硬编码回退）

当注解和 `lyclaw.agent.defaults` 都未提供值时，系统使用这些内置常量。它们定义为静态内部类或常量文件：

```java
package lyjew.com.lyclaw.config;

/**
 * 硬编码系统默认值 — 最低优先级的回退层。
 * 当Agent注解和lyclaw.agent.defaults都没有提供值时使用。
 */
public final class AgentSystemDefaults {

    private AgentSystemDefaults() {}

    public static final String MODEL            = "deepseek-v4-flash";
    public static final String PROVIDER         = "deepseek";
    public static final String THINKING_DEFAULT = "off";
    public static final String VERBOSE_DEFAULT  = "";
    public static final String REASONING_DEFAULT = "";
    public static final boolean FAST_MODE       = false;
    public static final String CONTEXT_INJECTION = "always";
    public static final int BOOTSTRAP_MAX_CHARS = 20000;
    public static final int BOOTSTRAP_TOTAL_MAX_CHARS = 150000;
    public static final int CONTEXT_TOKENS      = 0;
    public static final String SANDBOX          = "none";
    public static final String DELEGATION_MODE  = "suggest";
    public static final int MAX_SPAWN_DEPTH     = 1;
    public static final int MAX_CHILDREN        = 5;
    public static final int MEMORY_GET_MAX_CHARS = 50000;
    public static final int TOOL_RESULT_MAX_CHARS = 80000;
    public static final int TOOL_RESULT_TOTAL_MAX_CHARS = 200000;
}
```

### 1.1.5 ResolvedAgentConfig（解析输出）

解析器生成一个完全解析、深度合并、只读的配置对象。

```java
package lyjew.com.lyclaw.config;

import java.util.*;

/**
 * 完全解析的Agent配置 — 3层深度合并的输出。
 *
 * <p>每个字段都经过以下解析:
 *   agent注解 > lyclaw.agent.defaults.* > AgentSystemDefaults
 *
 * <p>此类在构造后是不可变的，以防止在Agent运行生命周期中意外修改。
 */
public class ResolvedAgentConfig {

    // ── 身份标识 ──
    private final String agentId;
    private final String agentName;
    private final String description;
    private final String version;
    private final boolean defaultAgent;

    // ── 工作区 ──
    private final String workspaceDir;
    private final String agentDir;

    // ── 系统提示词 ──
    private final String systemPromptOverride;

    // ── 模型 ──
    private final String model;
    private final String provider;
    private final List<String> fallbacks;

    // ── 思考 / 详细度 / 推理 ──
    private final String thinkingDefault;
    private final String verboseDefault;
    private final String reasoningDefault;
    private final boolean fastModeDefault;

    // ── 上下文 ──
    private final int contextTokens;
    private final String contextInjection;
    private final int bootstrapMaxChars;
    private final int bootstrapTotalMaxChars;

    // ── 技能 ──
    private final List<String> skills;

    // ── 委托 ──
    private final String delegationMode;
    private final List<String> allowAgents;
    private final int maxSpawnDepth;
    private final int maxChildrenPerAgent;

    // ── 沙箱 ──
    private final String sandbox;

    // ── 扩展（来自 @Extension[] 的剩余键值对） ──
    private final Map<String, String> extensions;

    // ── 运行时配置（从默认值复制） ──
    private final AgentDefaultsConfig.HeartbeatDefaults heartbeat;
    private final AgentDefaultsConfig.RunRetryDefaults runRetries;
    private final AgentDefaultsConfig.ContextLimitsDefaults contextLimits;

    // 私有构造函数 — 通过AgentConfigResolver使用Builder
    private ResolvedAgentConfig(Builder builder) {
        this.agentId              = builder.agentId;
        this.agentName            = builder.agentName;
        this.description          = builder.description;
        this.version              = builder.version;
        this.defaultAgent         = builder.defaultAgent;
        this.workspaceDir         = builder.workspaceDir;
        this.agentDir             = builder.agentDir;
        this.systemPromptOverride = builder.systemPromptOverride;
        this.model                = builder.model;
        this.provider             = builder.provider;
        this.fallbacks            = List.copyOf(builder.fallbacks);
        this.thinkingDefault      = builder.thinkingDefault;
        this.verboseDefault       = builder.verboseDefault;
        this.reasoningDefault     = builder.reasoningDefault;
        this.fastModeDefault      = builder.fastModeDefault;
        this.contextTokens        = builder.contextTokens;
        this.contextInjection     = builder.contextInjection;
        this.bootstrapMaxChars    = builder.bootstrapMaxChars;
        this.bootstrapTotalMaxChars = builder.bootstrapTotalMaxChars;
        this.skills               = List.copyOf(builder.skills);
        this.delegationMode       = builder.delegationMode;
        this.allowAgents          = List.copyOf(builder.allowAgents);
        this.maxSpawnDepth        = builder.maxSpawnDepth;
        this.maxChildrenPerAgent  = builder.maxChildrenPerAgent;
        this.sandbox              = builder.sandbox;
        this.extensions           = Collections.unmodifiableMap(new HashMap<>(builder.extensions));
        this.heartbeat            = builder.heartbeat;
        this.runRetries           = builder.runRetries;
        this.contextLimits        = builder.contextLimits;
    }

    // ===== Getters =====

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public boolean isDefaultAgent() { return defaultAgent; }
    public String getWorkspaceDir() { return workspaceDir; }
    public String getAgentDir() { return agentDir; }
    public String getSystemPromptOverride() { return systemPromptOverride; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public List<String> getFallbacks() { return fallbacks; }
    public String getThinkingDefault() { return thinkingDefault; }
    public String getVerboseDefault() { return verboseDefault; }
    public String getReasoningDefault() { return reasoningDefault; }
    public boolean isFastModeDefault() { return fastModeDefault; }
    public int getContextTokens() { return contextTokens; }
    public String getContextInjection() { return contextInjection; }
    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public List<String> getSkills() { return skills; }
    public String getDelegationMode() { return delegationMode; }
    public List<String> getAllowAgents() { return allowAgents; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public String getSandbox() { return sandbox; }
    public Map<String, String> getExtensions() { return extensions; }
    public AgentDefaultsConfig.HeartbeatDefaults getHeartbeat() { return heartbeat; }
    public AgentDefaultsConfig.RunRetryDefaults getRunRetries() { return runRetries; }
    public AgentDefaultsConfig.ContextLimitsDefaults getContextLimits() { return contextLimits; }

    // ===== Builder =====

    public static class Builder {
        private String agentId = "";
        private String agentName = "";
        private String description = "";
        private String version = "1.0.0";
        private boolean defaultAgent = false;
        private String workspaceDir = "";
        private String agentDir = "";
        private String systemPromptOverride = "";
        private String model = "";
        private String provider = "";
        private List<String> fallbacks = List.of();
        private String thinkingDefault = "";
        private String verboseDefault = "";
        private String reasoningDefault = "";
        private boolean fastModeDefault = false;
        private int contextTokens = 0;
        private String contextInjection = "always";
        private int bootstrapMaxChars = 20000;
        private int bootstrapTotalMaxChars = 150000;
        private List<String> skills = List.of();
        private String delegationMode = "suggest";
        private List<String> allowAgents = List.of();
        private int maxSpawnDepth = 1;
        private int maxChildrenPerAgent = 5;
        private String sandbox = "none";
        private Map<String, String> extensions = new HashMap<>();
        private AgentDefaultsConfig.HeartbeatDefaults heartbeat = new AgentDefaultsConfig.HeartbeatDefaults();
        private AgentDefaultsConfig.RunRetryDefaults runRetries = new AgentDefaultsConfig.RunRetryDefaults();
        private AgentDefaultsConfig.ContextLimitsDefaults contextLimits = new AgentDefaultsConfig.ContextLimitsDefaults();

        // （每个字段的setter — 为简洁省略，遵循以下模式:）

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder defaultAgent(boolean v) { this.defaultAgent = v; return this; }
        public Builder workspaceDir(String v) { this.workspaceDir = v; return this; }
        public Builder agentDir(String v) { this.agentDir = v; return this; }
        public Builder systemPromptOverride(String v) { this.systemPromptOverride = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder fallbacks(List<String> v) { this.fallbacks = v; return this; }
        public Builder thinkingDefault(String v) { this.thinkingDefault = v; return this; }
        public Builder verboseDefault(String v) { this.verboseDefault = v; return this; }
        public Builder reasoningDefault(String v) { this.reasoningDefault = v; return this; }
        public Builder fastModeDefault(boolean v) { this.fastModeDefault = v; return this; }
        public Builder contextTokens(int v) { this.contextTokens = v; return this; }
        public Builder contextInjection(String v) { this.contextInjection = v; return this; }
        public Builder bootstrapMaxChars(int v) { this.bootstrapMaxChars = v; return this; }
        public Builder bootstrapTotalMaxChars(int v) { this.bootstrapTotalMaxChars = v; return this; }
        public Builder skills(List<String> v) { this.skills = v; return this; }
        public Builder delegationMode(String v) { this.delegationMode = v; return this; }
        public Builder allowAgents(List<String> v) { this.allowAgents = v; return this; }
        public Builder maxSpawnDepth(int v) { this.maxSpawnDepth = v; return this; }
        public Builder maxChildrenPerAgent(int v) { this.maxChildrenPerAgent = v; return this; }
        public Builder sandbox(String v) { this.sandbox = v; return this; }
        public Builder extensions(Map<String, String> v) { this.extensions.clear(); this.extensions.putAll(v); return this; }
        public Builder heartbeat(AgentDefaultsConfig.HeartbeatDefaults v) { this.heartbeat = v; return this; }
        public Builder runRetries(AgentDefaultsConfig.RunRetryDefaults v) { this.runRetries = v; return this; }
        public Builder contextLimits(AgentDefaultsConfig.ContextLimitsDefaults v) { this.contextLimits = v; return this; }

        public ResolvedAgentConfig build() {
            return new ResolvedAgentConfig(this);
        }
    }
}
```

### 1.1.6 AgentConfigResolver增强

解析器增强了3层深度合并、列出Agent和支持工作区目录解析。

```java
package lyjew.com.lyclaw.config;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent配置解析器 — 执行3层深度合并:
 *   第1层: AgentSystemDefaults（硬编码）
 *   第2层: AgentDefaultsConfig（lyclaw.agent.defaults.*）
 *   第3层: @Agent 注解（Agent级别）
 *
 * <p>每个字段使用最高层中第一个非空/非默认值。
 * 列表是替换，不是合并（如果注解非空，则完全胜出）。
 * Map（扩展）是加法合并（键冲突时注解胜出）。
 */
public class AgentConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigResolver.class);

    private final AgentDefaultsConfig defaults;

    /** 缓存: agentId → ResolvedAgentConfig。配置刷新时失效。 */
    private final Map<String, ResolvedAgentConfig> cache = new ConcurrentHashMap<>();

    /** 已注册的Agent条目: agentId → @Agent注解(来自类)。 */
    private final Map<String, Agent> agentRegistry = new ConcurrentHashMap<>();

    public AgentConfigResolver(AgentDefaultsConfig defaults) {
        this.defaults = defaults;
    }

    /**
     * 注册Agent类以便后续解析。
     * 由AgentInterfaceProcessor在BFPP扫描期间调用。
     */
    public void registerAgent(String agentId, Agent ann) {
        agentRegistry.put(agentId, ann);
    }

    /**
     * 为给定Agent解析完整合并后的配置。
     *
     * 每个字段的解析规则:
     *   1. 如果 @Agent 字段已设置（非空字符串、非零int、非false boolean、非空列表），
     *      使用它。
     *   2. 否则如果 AgentDefaultsConfig 有非默认值，使用它。
     *   3. 否则使用 AgentSystemDefaults。
     */
    public ResolvedAgentConfig resolveAgentConfig(String agentId) {
        return cache.computeIfAbsent(agentId, id -> {
            Agent ann = agentRegistry.get(id);
            ResolvedAgentConfig.Builder b = new ResolvedAgentConfig.Builder();

            // ── 身份标识 ──
            b.agentId(id);
            b.agentName(resolveString(
                    ann != null ? ann.name() : "", defaultsField(null, "name"), id));
            b.description(resolveString(
                    ann != null ? ann.description() : "", "", ""));
            b.version(resolveString(
                    ann != null ? ann.version() : "", "1.0.0", "1.0.0"));
            b.defaultAgent(ann != null && ann.defaultAgent());

            // ── 工作区 ──
            b.workspaceDir(resolveString(
                    ann != null ? ann.workspace() : "",
                    defaults.getWorkspace(), ""));
            b.agentDir(resolveString(
                    ann != null ? ann.agentDir() : "", "", id));

            // ── 系统提示词 ──
            b.systemPromptOverride(resolveString(
                    ann != null ? ann.systemPromptOverride() : "", "", ""));

            // ── 模型 ──
            b.model(resolveString(
                    ann != null ? ann.model() : "",
                    defaults.getModel(), AgentSystemDefaults.MODEL));
            b.provider(resolveString(
                    ann != null ? ann.provider() : "",
                    defaults.getProvider(), AgentSystemDefaults.PROVIDER));
            b.fallbacks(resolveList(
                    ann != null ? List.of(ann.fallbacks()) : List.of(),
                    defaults.getFallbacks()));

            // ── 思考 / 详细度 / 推理 ──
            b.thinkingDefault(resolveString(
                    ann != null ? ann.thinkingDefault() : "",
                    defaults.getThinkingDefault(), AgentSystemDefaults.THINKING_DEFAULT));
            b.verboseDefault(resolveString(
                    ann != null ? ann.verboseDefault() : "",
                    defaults.getVerboseDefault(), AgentSystemDefaults.VERBOSE_DEFAULT));
            b.reasoningDefault(resolveString(
                    ann != null ? ann.reasoningDefault() : "",
                    defaults.getReasoningDefault(), AgentSystemDefaults.REASONING_DEFAULT));
            b.fastModeDefault(
                    (ann != null && ann.fastModeDefault())
                            || (!(ann != null && ann.fastModeDefault()) && defaults.isFastModeDefault()));

            // ── 上下文 ──
            b.contextTokens(resolveInt(
                    ann != null ? ann.contextTokens() : 0,
                    defaults.getContextTokens(), AgentSystemDefaults.CONTEXT_TOKENS));
            b.contextInjection(resolveString(
                    ann != null ? ann.contextInjection() : "",
                    defaults.getContextInjection(), AgentSystemDefaults.CONTEXT_INJECTION));
            b.bootstrapMaxChars(resolveInt(
                    ann != null ? ann.bootstrapMaxChars() : 0,
                    defaults.getBootstrapMaxChars(), AgentSystemDefaults.BOOTSTRAP_MAX_CHARS));
            b.bootstrapTotalMaxChars(resolveInt(
                    ann != null ? ann.bootstrapTotalMaxChars() : 0,
                    defaults.getBootstrapTotalMaxChars(), AgentSystemDefaults.BOOTSTRAP_TOTAL_MAX_CHARS));

            // ── 技能 ──
            b.skills(resolveList(
                    ann != null ? List.of(ann.skills()) : List.of(),
                    defaults.getSkills()));

            // ── 委托 ──
            b.delegationMode(resolveString(
                    ann != null ? ann.delegationMode() : "",
                    defaults.getSubagents().getDelegationMode(), AgentSystemDefaults.DELEGATION_MODE));
            b.allowAgents(resolveList(
                    ann != null ? List.of(ann.allowAgents()) : List.of(),
                    defaults.getSubagents().getAllowAgents()));
            b.maxSpawnDepth(resolveInt(
                    ann != null ? ann.maxSpawnDepth() : 0,
                    defaults.getSubagents().getMaxSpawnDepth(), AgentSystemDefaults.MAX_SPAWN_DEPTH));
            b.maxChildrenPerAgent(resolveInt(
                    ann != null ? ann.maxChildrenPerAgent() : 0,
                    defaults.getSubagents().getMaxChildrenPerAgent(), AgentSystemDefaults.MAX_CHILDREN));

            // ── 沙箱 ──
            b.sandbox(resolveString(
                    ann != null ? ann.sandbox() : "",
                    defaults.getSandbox(), AgentSystemDefaults.SANDBOX));

            // ── 扩展: 将注解扩展合并到任何默认值之上
            Map<String, String> extMap = new HashMap<>();
            if (ann != null) {
                for (Extension ext : ann.extensions()) {
                    extMap.put(ext.key(), ext.value());
                }
            }
            b.extensions(extMap);

            // ── 运行时配置（直接从默认值复制，无需注解覆盖） ──
            b.heartbeat(defaults.getHeartbeat());
            b.runRetries(defaults.getRunRetries());
            b.contextLimits(defaults.getContextLimits());

            log.debug("ResolvedAgentConfig for {}: model={} provider={} sandbox={}",
                    id, b.build().getModel(), b.build().getProvider(), b.build().getSandbox());
            return b.build();
        });
    }

    /**
     * 列出所有已注册的Agent ID。
     */
    public Set<String> listAgentIds() {
        return Collections.unmodifiableSet(agentRegistry.keySet());
    }

    /**
     * 列出所有已注册的Agent条目，作为 (id, name, description) 三元组。
     */
    public List<AgentEntry> listAgentEntries() {
        return agentRegistry.entrySet().stream()
                .map(e -> new AgentEntry(e.getKey(),
                        e.getValue().name().isEmpty() ? e.getKey() : e.getValue().name(),
                        e.getValue().description()))
                .collect(Collectors.toList());
    }

    /**
     * 解析默认Agent id。返回defaultAgent=true的Agent，
     * 或第一个注册的Agent，或 "default"。
     */
    public String resolveDefaultAgentId() {
        return agentRegistry.entrySet().stream()
                .filter(e -> e.getValue().defaultAgent())
                .map(Map.Entry::getKey)
                .findFirst()
                .or(() -> agentRegistry.keySet().stream().findFirst())
                .orElse("default");
    }

    /**
     * 解析Agent的完整工作区目录。
     * 通常为: {workspaceRoot}/{agentDir}
     */
    public String resolveAgentWorkspaceDir(ResolvedAgentConfig config) {
        String root = !config.getWorkspaceDir().isEmpty()
                ? config.getWorkspaceDir()
                : defaults.getWorkspace();
        if (root == null || root.isEmpty()) {
            root = System.getProperty("user.dir");
        }
        String dir = !config.getAgentDir().isEmpty() ? config.getAgentDir() : config.getAgentId();
        return root.endsWith("/") ? root + dir : root + "/" + dir;
    }

    /**
     * 使配置缓存失效（在配置刷新事件时调用）。
     */
    public void invalidate() {
        cache.clear();
    }

    // ===== 私有解析辅助方法 =====

    /** 解析可为null的Object字段: 返回第一个非null/非空白的值。 */
    private String resolveString(String agentVal, String defaultsVal, String systemVal) {
        if (agentVal != null && !agentVal.isEmpty()) return agentVal;
        if (defaultsVal != null && !defaultsVal.isEmpty()) return defaultsVal;
        return systemVal != null ? systemVal : "";
    }

    private int resolveInt(int agentVal, int defaultsVal, int systemVal) {
        if (agentVal != 0) return agentVal;
        if (defaultsVal != 0) return defaultsVal;
        return systemVal;
    }

    /** 解析列表: 如果Agent级别非空则使用它，否则使用默认值。 */
    private List<String> resolveList(List<String> agentVal, List<String> defaultsVal) {
        if (agentVal != null && !agentVal.isEmpty()) return agentVal;
        return defaultsVal != null ? defaultsVal : List.of();
    }

    /** 不在AgentDefaultsConfig根级别的字段占位符。 */
    private String defaultsField(AgentDefaultsConfig d, String field) {
        if (d == null) return "";
        return switch (field) {
            case "name" -> "";
            default -> "";
        };
    }

    // ===== 数据记录 =====

    public record AgentEntry(String id, String name, String description) {}
}
```

### 1.1.7 YAML配置示例

```yaml
# application.yml — Agent配置

lyclaw:
  agent:
    # 所有Agent继承的全局默认值
    defaults:
      model: "deepseek-v4-flash"
      provider: "deepseek"
      fallbacks:
        - "deepseek-v4-pro"
        - "openai-gpt-4o"
      thinkingDefault: "off"
      verboseDefault: ""
      reasoningDefault: ""
      fastModeDefault: false
      contextInjection: "always"
      bootstrapMaxChars: 20000
      bootstrapTotalMaxChars: 150000
      contextTokens: 0
      skills: []
      sandbox: "none"
      workspace: "/var/lyclaw/workspaces"

      # 子Agent委托默认值
      subagents:
        delegationMode: "suggest"
        allowAgents: []           # 空 = 允许全部
        maxSpawnDepth: 1
        maxChildrenPerAgent: 5

      # 心跳检测: 对长时间运行的Agent进行周期性存活检查
      heartbeat:
        enabled: false
        intervalSeconds: 60
        maxIdleSeconds: 300

      # 模型失败时的运行重试
      runRetries:
        maxAttempts: 3
        baseDelayMs: 1000
        backoff: "exponential"

      # 上下文限制: 裁剪工具输出 / 内存以保持在窗口内
      contextLimits:
        memoryGetMaxChars: 50000
        toolResultMaxChars: 80000
        toolResultTotalMaxChars: 200000

    # 每个Agent的覆盖配置（遗留路径 "lyclaw.agents" — 保留用于向后兼容）
    agents:
      code-reviewer:
        systemPromptOverride: "你是一位专家级代码审查员。请彻底但简洁地审查。"
        model: "deepseek-v4-pro"
        thinkingDefault: "high"
        maxToolRounds: 20
```

### 1.1.8 注解使用示例

```java
package com.example.agents;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.annotation.agent.V;

/**
 * 代码审查Agent — 使用专业模型和高思考级别以输出高质量结果。
 */
@Agent(
    id          = "code-reviewer",
    defaultAgent = false,
    name        = "Code Reviewer",
    description = "审查代码变更中的错误、风格和安全问题",
    version     = "2.0.0",
    model       = "deepseek-v4-pro",
    thinkingDefault = "high",
    sandbox     = "docker",
    skills      = {"code-analysis", "security-scan"},
    delegationMode = "prefer",
    allowAgents = {"tester", "linter"},
    maxSpawnDepth = 2,
    maxChildrenPerAgent = 3,
    contextInjection = "always"
)
public interface CodeReviewerAgent {

    @UserMessage("审查以下代码变更:\n\n{{diff}}")
    String review(@V("diff") String diff);

    @UserMessage("审查仓库 {{repo}} 中的PR #{{prNumber}}")
    String reviewPullRequest(@V("prNumber") int prNumber, @V("repo") String repo);
}
```

---

## 1.2 AgentContext增强

### 1.2.1 问题

当前 `AgentContext` 是一个扁平POJO，具有 `sessionId`、`userMessage`、`systemPrompt`、`toolRegistry`、`method`、`args` 等字段，以及一些Pipeline状态的原子变量。它缺乏对Agent已解析配置的感知、没有工作区路径、没有运行时类型感知，也没有子Agent跟踪。

### 1.2.2 增强的 AgentContext

```java
package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

/**
 * 增强的AgentContext — 所有Hook、阶段和运行时操作的统一数据总线。
 *
 * <h3>新增字段（第一阶段新增用粗体标出）:</h3>
 * <ul>
 *   <li><b>agentId, agentName</b> — 来自ResolvedAgentConfig</li>
 *   <li><b>workspaceDir, agentDir</b> — 已解析的文件系统路径</li>
 *   <li><b>resolvedConfig</b> — 完全合并的ResolvedAgentConfig</li>
 *   <li><b>bootstrapContent</b> — 加载的AGENTS.md、CLAUDE.md内容</li>
 *   <li><b>contextLimits</b> — 内存/工具结果大小上限</li>
 *   <li><b>thinkingLevel, verboseLevel, reasoningLevel</b> — 生效的级别</li>
 *   <li><b>delegationMode, allowAgents, maxSpawnDepth, maxChildrenPerAgent</b></li>
 *   <li><b>activeSubagentIds</b> — 跟踪生成的子Agent</li>
 *   <li><b>runtimeType</b> — EMBEDDED 或 ACP</li>
 *   <li><b>runMetadata</b> — runId, jobId, trigger, channelId</li>
 * </ul>
 */
public class AgentContext {

    public enum Lifecycle { TRANSIENT, SESSION, PERSISTENT }

    /**
     * 哪个运行时引擎支持此Agent调用。
     */
    public enum AgentRuntimeType {
        /** LyClaw内置的ReAct引擎。 */
        EMBEDDED,
        /** 通过Agent Communication Protocol的外部Agent后端。 */
        ACP
    }

    // ==================== Agent身份标识（新增） ====================

    private final String agentId;
    private final String agentName;
    private final ResolvedAgentConfig resolvedConfig;

    // ==================== 工作区（新增） ====================

    private final String workspaceDir;
    private final String agentDir;

    // ==================== 引导内容（新增） ====================

    /**
     * 从AGENTS.md、CLAUDE.md、system.md等加载的内容。
     * Key = 文件名, Value = 文件内容（截断至bootstrapMaxChars）。
     */
    private final Map<String, Object> bootstrapContent = new LinkedHashMap<>();

    // ==================== 上下文限制（新增） ====================

    /** 内存检索的最大字符数。 */
    private int memoryGetMaxChars = 50000;
    /** 单个工具结果的最大字符数。 */
    private int toolResultMaxChars = 80000;
    /** 所有工具结果合计的最大字符数。 */
    private int toolResultTotalMaxChars = 200000;

    // ==================== 思考 / 详细度 / 推理（新增） ====================

    private String thinkingLevel = "off";
    private String verboseLevel = "";
    private String reasoningLevel = "";

    // ==================== 子Agent委托（新增） ====================

    private String delegationMode = "suggest";
    private List<String> allowAgents = List.of();
    private int maxSpawnDepth = 1;
    private int maxChildrenPerAgent = 5;

    /** 跟踪此Agent当前正在运行的子Agent id。 */
    private final List<String> activeSubagentIds = new CopyOnWriteArrayList<>();

    // ==================== 运行时类型（新增） ====================

    private AgentRuntimeType runtimeType = AgentRuntimeType.EMBEDDED;

    // ==================== 运行元数据（新增） ====================

    /**
     * 关于运行的任意元数据: runId, jobId, trigger（如 "webhook"）,
     * channelId（如 Slack 频道）等。
     */
    private final Map<String, Object> runMetadata = new LinkedHashMap<>();

    // ==================== 遗留字段（未更改） ====================

    private final String sessionId;
    private String userMessage;
    private String systemPrompt;
    private ChatRequest chatRequest;
    private final ToolRegistry toolRegistry;
    private final Method method;
    private final Object[] args;
    private SandboxLevel sandboxLevel;
    private Lifecycle lifecycle = Lifecycle.TRANSIENT;

    private final TraceContext tracing;

    private final List<String> toolResults = new CopyOnWriteArrayList<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final List<TaskNode> nodes = new CopyOnWriteArrayList<>();
    private final AtomicReference<Double> reflectScoreRef = new AtomicReference<>(0.0);
    private final AtomicBoolean pipelineOk = new AtomicBoolean(false);
    private final AtomicLong respondStartMs = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicReference<String> currentStage = new AtomicReference<>("init");

    private final Map<String, Object> attributes = new HashMap<>();

    // ==================== 构造函数 ====================

    /**
     * 带ResolvedAgentConfig的完整构造函数。
     */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args,
                        ResolvedAgentConfig resolvedConfig) {
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.method = method;
        this.args = args;
        this.tracing = new TraceContext();

        // 从已解析配置填充
        this.resolvedConfig = resolvedConfig;
        this.agentId = resolvedConfig.getAgentId();
        this.agentName = resolvedConfig.getAgentName();
        this.workspaceDir = resolvedConfig.getWorkspaceDir();
        this.agentDir = resolvedConfig.getAgentDir();
        this.thinkingLevel = resolvedConfig.getThinkingDefault();
        this.verboseLevel = resolvedConfig.getVerboseDefault();
        this.reasoningLevel = resolvedConfig.getReasoningDefault();
        this.delegationMode = resolvedConfig.getDelegationMode();
        this.allowAgents = resolvedConfig.getAllowAgents();
        this.maxSpawnDepth = resolvedConfig.getMaxSpawnDepth();
        this.maxChildrenPerAgent = resolvedConfig.getMaxChildrenPerAgent();

        if (resolvedConfig.getContextLimits() != null) {
            this.memoryGetMaxChars = resolvedConfig.getContextLimits().getMemoryGetMaxChars();
            this.toolResultMaxChars = resolvedConfig.getContextLimits().getToolResultMaxChars();
            this.toolResultTotalMaxChars = resolvedConfig.getContextLimits().getToolResultTotalMaxChars();
        }
    }

    /** 向后兼容的构造函数（无ResolvedAgentConfig）。 */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args) {
        this(sessionId, userMessage, systemPrompt, toolRegistry, method, args, null);
    }

    // ==================== 新增 Getters/Setters ====================

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public ResolvedAgentConfig getResolvedConfig() { return resolvedConfig; }
    public String getWorkspaceDir() { return workspaceDir; }
    public String getAgentDir() { return agentDir; }

    public Map<String, Object> getBootstrapContent() { return bootstrapContent; }
    public void addBootstrapContent(String filename, Object content) {
        this.bootstrapContent.put(filename, content);
    }

    public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
    public void setMemoryGetMaxChars(int v) { this.memoryGetMaxChars = v; }
    public int getToolResultMaxChars() { return toolResultMaxChars; }
    public void setToolResultMaxChars(int v) { this.toolResultMaxChars = v; }
    public int getToolResultTotalMaxChars() { return toolResultTotalMaxChars; }
    public void setToolResultTotalMaxChars(int v) { this.toolResultTotalMaxChars = v; }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String v) { this.thinkingLevel = v; }
    public String getVerboseLevel() { return verboseLevel; }
    public void setVerboseLevel(String v) { this.verboseLevel = v; }
    public String getReasoningLevel() { return reasoningLevel; }
    public void setReasoningLevel(String v) { this.reasoningLevel = v; }

    public String getDelegationMode() { return delegationMode; }
    public void setDelegationMode(String v) { this.delegationMode = v; }
    public List<String> getAllowAgents() { return allowAgents; }
    public void setAllowAgents(List<String> v) { this.allowAgents = v; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int v) { this.maxSpawnDepth = v; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public void setMaxChildrenPerAgent(int v) { this.maxChildrenPerAgent = v; }

    public List<String> getActiveSubagentIds() { return activeSubagentIds; }
    public void addActiveSubagentId(String id) { this.activeSubagentIds.add(id); }
    public void removeActiveSubagentId(String id) { this.activeSubagentIds.remove(id); }

    public AgentRuntimeType getRuntimeType() { return runtimeType; }
    public void setRuntimeType(AgentRuntimeType v) { this.runtimeType = v; }

    public Map<String, Object> getRunMetadata() { return runMetadata; }
    public void setRunMetadata(String key, Object value) { this.runMetadata.put(key, value); }
    @SuppressWarnings("unchecked")
    public <T> T getRunMetadata(String key) { return (T) runMetadata.get(key); }

    // ==================== 遗留 Getters（未更改） ====================

    public String getSessionId() { return sessionId; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public ChatRequest getChatRequest() { return chatRequest; }
    public void setChatRequest(ChatRequest chatRequest) { this.chatRequest = chatRequest; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public Method getMethod() { return method; }
    public Object[] getArgs() { return args; }
    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
    public void setSandboxLevel(SandboxLevel sandboxLevel) { this.sandboxLevel = sandboxLevel; }
    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
    public TraceContext getTracing() { return tracing; }
    public List<String> getToolResults() { return toolResults; }
    public void addToolResult(String result) { toolResults.add(result); }
    public AtomicInteger getSuccessCount() { return successCount; }
    public AtomicInteger getFailCount() { return failCount; }
    public List<TaskNode> getNodes() { return nodes; }
    public void addNode(TaskNode node) { nodes.add(node); }
    public AtomicReference<Double> getReflectScoreRef() { return reflectScoreRef; }
    public AtomicBoolean getPipelineOk() { return pipelineOk; }
    public boolean isPipelineOk() { return pipelineOk.get(); }
    public void setPipelineOk(boolean value) { pipelineOk.set(value); }
    public AtomicLong getRespondStartMs() { return respondStartMs; }
    public AtomicBoolean getTerminated() { return terminated; }
    public boolean isTerminated() { return terminated.get(); }
    public void setTerminated(boolean value) { terminated.set(value); }
    public AtomicReference<String> getCurrentStage() { return currentStage; }
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Map<String, Object> getAttributes() { return attributes; }

    // ==================== 增强的快照/恢复 ====================

    /**
     * 增强的快照 — 包含所有新字段。
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        // 遗留
        snapshot.put("sessionId", sessionId);
        snapshot.put("userMessage", userMessage);
        snapshot.put("systemPrompt", systemPrompt);
        snapshot.put("sandboxLevel", sandboxLevel != null ? sandboxLevel.name() : null);
        snapshot.put("lifecycle", lifecycle.name());
        snapshot.put("currentStage", currentStage.get());
        snapshot.put("successCount", successCount.get());
        snapshot.put("failCount", failCount.get());
        snapshot.put("pipelineOk", pipelineOk.get());
        snapshot.put("terminated", terminated.get());
        snapshot.put("reflectScore", reflectScoreRef.get());
        snapshot.put("toolResults", new ArrayList<>(toolResults));
        snapshot.put("tracing", Map.of("traceId", tracing.getTraceId()));

        // 新增 — 身份标识
        snapshot.put("agentId", agentId);
        snapshot.put("agentName", agentName);

        // 新增 — 工作区
        snapshot.put("workspaceDir", workspaceDir);
        snapshot.put("agentDir", agentDir);

        // 新增 — 级别
        snapshot.put("thinkingLevel", thinkingLevel);
        snapshot.put("verboseLevel", verboseLevel);
        snapshot.put("reasoningLevel", reasoningLevel);

        // 新增 — 委托
        snapshot.put("delegationMode", delegationMode);
        snapshot.put("allowAgents", new ArrayList<>(allowAgents));
        snapshot.put("maxSpawnDepth", maxSpawnDepth);
        snapshot.put("maxChildrenPerAgent", maxChildrenPerAgent);

        // 新增 — 上下文限制
        snapshot.put("memoryGetMaxChars", memoryGetMaxChars);
        snapshot.put("toolResultMaxChars", toolResultMaxChars);
        snapshot.put("toolResultTotalMaxChars", toolResultTotalMaxChars);

        // 新增 — 运行时
        snapshot.put("runtimeType", runtimeType.name());
        snapshot.put("activeSubagentIds", new ArrayList<>(activeSubagentIds));
        snapshot.put("runMetadata", new HashMap<>(runMetadata));

        // 新增 — 引导内容
        snapshot.put("bootstrapContent", new HashMap<>(bootstrapContent));

        return snapshot;
    }

    /**
     * 从快照恢复。运行时引用（toolRegistry, method, args）
     * 必须由调用者重新注入。
     */
    @SuppressWarnings("unchecked")
    public void restoreFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) return;

        // 遗留
        if (snapshot.get("sandboxLevel") != null)
            this.sandboxLevel = SandboxLevel.valueOf((String) snapshot.get("sandboxLevel"));
        if (snapshot.get("lifecycle") != null)
            this.lifecycle = Lifecycle.valueOf((String) snapshot.get("lifecycle"));
        if (snapshot.get("currentStage") != null)
            this.currentStage.set((String) snapshot.get("currentStage"));
        if (snapshot.get("successCount") != null)
            this.successCount.set(((Number) snapshot.get("successCount")).intValue());
        if (snapshot.get("failCount") != null)
            this.failCount.set(((Number) snapshot.get("failCount")).intValue());
        if (snapshot.get("pipelineOk") != null)
            this.pipelineOk.set((Boolean) snapshot.get("pipelineOk"));
        if (snapshot.get("terminated") != null)
            this.terminated.set((Boolean) snapshot.get("terminated"));
        if (snapshot.get("reflectScore") != null)
            this.reflectScoreRef.set(((Number) snapshot.get("reflectScore")).doubleValue());
        if (snapshot.get("toolResults") instanceof List<?> list) {
            this.toolResults.clear();
            for (Object item : list) this.toolResults.add((String) item);
        }

        // 新增 — 身份标识
        if (snapshot.get("agentId") != null)
            this.setRunMetadata("restoredAgentId", snapshot.get("agentId"));

        // 新增 — 级别
        if (snapshot.get("thinkingLevel") != null)
            this.thinkingLevel = (String) snapshot.get("thinkingLevel");
        if (snapshot.get("verboseLevel") != null)
            this.verboseLevel = (String) snapshot.get("verboseLevel");
        if (snapshot.get("reasoningLevel") != null)
            this.reasoningLevel = (String) snapshot.get("reasoningLevel");

        // 新增 — 委托
        if (snapshot.get("delegationMode") != null)
            this.delegationMode = (String) snapshot.get("delegationMode");
        if (snapshot.get("allowAgents") instanceof List<?> al)
            this.allowAgents = al.stream().map(Object::toString).toList();
        if (snapshot.get("maxSpawnDepth") instanceof Number n)
            this.maxSpawnDepth = n.intValue();
        if (snapshot.get("maxChildrenPerAgent") instanceof Number n)
            this.maxChildrenPerAgent = n.intValue();

        // 新增 — 上下文限制
        if (snapshot.get("memoryGetMaxChars") instanceof Number n)
            this.memoryGetMaxChars = n.intValue();
        if (snapshot.get("toolResultMaxChars") instanceof Number n)
            this.toolResultMaxChars = n.intValue();
        if (snapshot.get("toolResultTotalMaxChars") instanceof Number n)
            this.toolResultTotalMaxChars = n.intValue();

        // 新增 — 运行时
        if (snapshot.get("runtimeType") != null)
            this.runtimeType = AgentRuntimeType.valueOf((String) snapshot.get("runtimeType"));
        if (snapshot.get("activeSubagentIds") instanceof List<?> sl) {
            this.activeSubagentIds.clear();
            for (Object item : sl) this.activeSubagentIds.add((String) item);
        }
        if (snapshot.get("runMetadata") instanceof Map<?, ?> rm) {
            this.runMetadata.clear();
            for (Map.Entry<?, ?> e : rm.entrySet())
                this.runMetadata.put((String) e.getKey(), e.getValue());
        }
        if (snapshot.get("bootstrapContent") instanceof Map<?, ?> bc) {
            this.bootstrapContent.clear();
            for (Map.Entry<?, ?> e : bc.entrySet())
                this.bootstrapContent.put((String) e.getKey(), e.getValue());
        }
    }

    // ==================== 工厂方法 ====================

    public static AgentContext sessionScoped(String sessionId, String userMessage,
                                             String systemPrompt, ToolRegistry toolRegistry,
                                             Method method, Object[] args,
                                             ResolvedAgentConfig resolvedConfig) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args, resolvedConfig);
        ctx.setLifecycle(Lifecycle.SESSION);
        return ctx;
    }

    public static AgentContext persistentScoped(String sessionId, String userMessage,
                                                 String systemPrompt, ToolRegistry toolRegistry,
                                                 Method method, Object[] args,
                                                 ResolvedAgentConfig resolvedConfig) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args, resolvedConfig);
        ctx.setLifecycle(Lifecycle.PERSISTENT);
        return ctx;
    }
}
```

---

## 1.3 Hook系统扩展（从5个到36个Hook）

### 1.3.1 问题

当前 `AgentHook` 只有5个扩展点：`beforeRequest`、`beforeModel`、`afterModel`、`wrapToolCall`、`wrapToolExecutor`、`afterResult`。无法Hook到会话生命周期、Agent启动/结束、子Agent生成、压缩、消息事件或心跳贡献。

### 1.3.2 完整的Hook接口

```java
package lyjew.com.lyclaw.react;

import java.util.List;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;

/**
 * 完整的Agent生命周期Hook SPI — 36个扩展点。
 *
 * <p>所有方法都是默认（无操作），因此实现者只需覆盖所需的方法。
 * Hook由 {@link AgentInvocationHandler} 在Agent生命周期的适当节点进行分发。
 *
 * <h3>执行顺序</h3>
 * <p>Hook在分发前按 {@link #getOrder()}（升序）排序。
 * 默认顺序为 100。</p>
 */
public interface AgentHook {

    // =====================================================================
    // 现有方法（保留用于向后兼容）
    // =====================================================================

    /** 在整个Agent调用Pipeline开始之前。
     *  抛出异常将中止请求。 */
    default void beforeRequest(AgentContext ctx) {}

    /** 每次LLM调用之前。可注入规划上下文或调整消息。 */
    default List<Message> beforeModel(List<Message> messages, AgentContext ctx) {
        return messages;
    }

    /** 每次LLM响应之后。可检测有害内容、记录日志或转换输出。 */
    default String afterModel(String response, AgentContext ctx) {
        return response;
    }

    /** 包装单个工具调用（比 wrapToolExecutor 更细粒度）。 */
    default ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) {
        return toolCall;
    }

    /** 包装 ToolExecutor，形成装饰器链。 */
    default ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        return inner;
    }

    /** 最终结果之后，返回给调用者之前。
     *  按逆序分发（afterResult Hook从高到低执行）。 */
    default String afterResult(String result, AgentContext ctx) {
        return result;
    }

    /** 优先级。较小数字先执行。默认: 100。 */
    default int getOrder() { return 100; }

    // =====================================================================
    // 新增 — 模型生命周期
    // =====================================================================

    /** 模型解析（提供商 + 模型选择）之前。 */
    default void beforeModelResolve(AgentContext ctx) {}

    /** 当模型调用开始时调用（路由之后、API调用之前）。 */
    default void modelCallStarted(AgentContext ctx) {}

    /** 当模型调用结束时调用（成功或失败）。 */
    default void modelCallEnded(AgentContext ctx) {}

    /** 原始LLM输入（发送给模型的最终组装提示词）。 */
    default void llmInput(String prompt, AgentContext ctx) {}

    /** 原始LLM输出（完整的模型响应，解析之前）。 */
    default void llmOutput(String response, AgentContext ctx) {}

    // =====================================================================
    // 新增 — Agent生命周期
    // =====================================================================

    /** Agent运行开始之前（Pipeline入口）。 */
    default void beforeAgentStart(AgentContext ctx) {}

    /**
     * Agent回复发送回调用者之前。
     * @param reply 草稿回复文本
     * @param ctx Agent上下文
     */
    default void beforeAgentReply(String reply, AgentContext ctx) {}

    /**
     * Agent最终化之前（ReAct循环结束后、清理之前）。
     * 可返回 CONTINUE（默认）、REVISE（带指令重试）或 FINALIZE（跳过修订）的决策。
     */
    default AgentFinalizeResult beforeAgentFinalize(AgentContext ctx) {
        return AgentFinalizeResult.continue_();
    }

    /** Agent运行完成之后（清理、指标收集、通知）。 */
    default void agentEnd(AgentContext ctx) {}

    /** 每次单独的Agent调用之前（代理上的每个方法调用）。 */
    default void beforeAgentRun(AgentContext ctx) {}

    // =====================================================================
    // 新增 — 工具生命周期
    // =====================================================================

    /** 工具调用之前。包含工具名称、调用ID、序列化参数。 */
    default void beforeToolCall(String toolName, String toolCallId, String args, AgentContext ctx) {}

    /** 工具完成之后。包含结果字符串（可能为错误）。 */
    default void afterToolCall(String toolName, String toolCallId, String result, AgentContext ctx) {}

    /** 工具结果持久化到消息历史之后。 */
    default void toolResultPersist(String toolName, String result, AgentContext ctx) {}

    // =====================================================================
    // 新增 — 会话生命周期
    // =====================================================================

    /** 当新的Agent会话创建时。 */
    default void sessionStart(String sessionId, AgentContext ctx) {}

    /** 当Agent会话结束时（正常关闭或超时）。 */
    default void sessionEnd(String sessionId, AgentContext ctx) {}

    // =====================================================================
    // 新增 — 子Agent生命周期
    // =====================================================================

    /** 子Agent生成之前。Hook可通过抛异常来阻止。 */
    default void subagentSpawning(String childAgentId, String task, AgentContext ctx) {}

    /** 子Agent成功生成并创建会话之后。 */
    default void subagentSpawned(String childAgentId, String sessionKey, AgentContext ctx) {}

    /** 子Agent完成之后（成功或失败）。 */
    default void subagentEnded(String childAgentId, String outcome, AgentContext ctx) {}

    // =====================================================================
    // 新增 — 压缩
    // =====================================================================

    /** 消息历史压缩之前（上下文窗口管理）。 */
    default void beforeCompaction(AgentContext ctx) {}

    /** 消息历史压缩之后。 */
    default void afterCompaction(AgentContext ctx) {}

    // =====================================================================
    // 新增 — 消息生命周期
    // =====================================================================

    /** 从调用者/用户收到一条消息。 */
    default void messageReceived(Message msg, AgentContext ctx) {}

    /** Agent即将发送一条消息（LLM调用之前）。 */
    default void messageSending(String msg, AgentContext ctx) {}

    /** 一条消息已发送给调用者。 */
    default void messageSent(String msg, AgentContext ctx) {}

    // =====================================================================
    // 新增 — 心跳检测
    // =====================================================================

    /**
     * 向发送给LLM的周期性心跳提示提供贡献内容，
     * 用于保持长时间运行的Agent存活并知晓其上下文。
     * @return 贡献字符串（追加到心跳提示），或 "" 表示无贡献。
     */
    default String heartbeatPromptContribution(AgentContext ctx) { return ""; }
}
```

### 1.3.3 AgentFinalizeResult

```java
package lyjew.com.lyclaw.react;

/**
 * 由 {@link AgentHook#beforeAgentFinalize(AgentContext)} 返回。
 * 控制Agent运行是已完成的、需要修订还是应即刻最终化。
 */
public class AgentFinalizeResult {

    public enum Action {
        /** 正常继续 — 进行最终化并返回结果。 */
        CONTINUE,
        /** 修订 — 使用retryInstruction重新循环到respond阶段。 */
        REVISE,
        /** 即刻最终化 — 跳过任何剩余的修订逻辑。 */
        FINALIZE
    }

    private final Action action;
    private final String reason;
    private final String retryInstruction;
    private final String idempotencyKey;
    private final int maxAttempts;

    private AgentFinalizeResult(Action action, String reason, String retryInstruction,
                                String idempotencyKey, int maxAttempts) {
        this.action = action;
        this.reason = reason;
        this.retryInstruction = retryInstruction;
        this.idempotencyKey = idempotencyKey;
        this.maxAttempts = maxAttempts;
    }

    // ===== 工厂方法 =====

    public static AgentFinalizeResult continue_() {
        return new AgentFinalizeResult(Action.CONTINUE, null, null, null, 1);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction, null, 3);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction,
                                              String idempotencyKey, int maxAttempts) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction,
                idempotencyKey, maxAttempts);
    }

    public static AgentFinalizeResult finalize(String reason) {
        return new AgentFinalizeResult(Action.FINALIZE, reason, null, null, 1);
    }

    // ===== Getters =====

    public Action getAction() { return action; }
    public String getReason() { return reason; }
    public String getRetryInstruction() { return retryInstruction; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getMaxAttempts() { return maxAttempts; }

    public boolean isContinue() { return action == Action.CONTINUE; }
    public boolean isRevise() { return action == Action.REVISE; }
    public boolean isFinalize() { return action == Action.FINALIZE; }
}
```

### 1.3.4 HookDecision（安全/审批）

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * 由控制执行门控的Hook返回的阻止/审批决策。
 * 供安全Hook、审批Hook等使用。
 */
public class HookDecision {

    public enum Outcome {
        /** 允许继续执行。 */
        PASS,
        /** 阻止执行。 */
        BLOCK
    }

    private final Outcome outcome;
    private final String reason;
    private final String message;       // 面向用户的消息
    private final String category;      // 如 "security", "approval", "rate-limit"
    private final Map<String, Object> metadata;

    private HookDecision(Outcome outcome, String reason, String message,
                         String category, Map<String, Object> metadata) {
        this.outcome = outcome;
        this.reason = reason;
        this.message = message;
        this.category = category;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static HookDecision pass() {
        return new HookDecision(Outcome.PASS, null, null, null, null);
    }

    public static HookDecision block(String reason, String message, String category) {
        return new HookDecision(Outcome.BLOCK, reason, message, category, null);
    }

    public static HookDecision block(String reason, String message, String category,
                                      Map<String, Object> metadata) {
        return new HookDecision(Outcome.BLOCK, reason, message, category, metadata);
    }

    public Outcome getOutcome() { return outcome; }
    public String getReason() { return reason; }
    public String getMessage() { return message; }
    public String getCategory() { return category; }
    public Map<String, Object> getMetadata() { return metadata; }
    public boolean isPass() { return outcome == Outcome.PASS; }
    public boolean isBlock() { return outcome == Outcome.BLOCK; }
}
```

### 1.3.5 HookRegistration（注册表条目）

```java
package lyjew.com.lyclaw.react;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * {@link HookRegistry}中已注册的Hook条目。
 *
 * @param pluginId    注册此Hook的插件/模块
 * @param hookName    Hook方法名称（如 "beforeModel", "afterToolCall"）
 * @param handler     处理器函数（签名因Hook而异）
 * @param priority    执行优先级（越小 = 越早）
 * @param timeoutMs   在Hook被视为挂起之前的最大执行时间（0 = 无超时）
 * @param source      Hook的注册方式（annotation, SPI, programmatic）
 */
public record HookRegistration(
        String pluginId,
        String hookName,
        Object handler,          // Function 或 BiConsumer，取决于Hook类型
        int priority,
        long timeoutMs,
        String source            // "annotation", "spi", "programmatic"
) {
    public HookRegistration {
        if (pluginId == null || pluginId.isBlank()) pluginId = "unknown";
        if (hookName == null || hookName.isBlank()) throw new IllegalArgumentException("hookName is required");
        if (handler == null) throw new IllegalArgumentException("handler is required");
        if (timeoutMs < 0) timeoutMs = 0;
        if (source == null || source.isBlank()) source = "programmatic";
    }

    public static HookRegistration of(String pluginId, String hookName,
                                       Object handler, int priority) {
        return new HookRegistration(pluginId, hookName, handler, priority, 0, "programmatic");
    }
}
```

### 1.3.6 HookRegistry

```java
package lyjew.com.lyclaw.react;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用于Hook管理和分发的中央注册表。
 *
 * <p>Hook按Hook名称（如 "beforeModel", "afterToolCall"）分组。
 * 分发时按优先级（升序）排序并按顺序调用。
 */
public class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    /** hookName → 已排序的注册列表。 */
    private final Map<String, List<HookRegistration>> registrations = new ConcurrentHashMap<>();

    /**
     * 注册一个Hook。如果Hook名称是新的，则创建列表。
     * 同一Hook名称的注册保持按优先级排序。
     */
    public void register(HookRegistration reg) {
        registrations.compute(reg.hookName(), (k, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(reg);
            list.sort(Comparator.comparingInt(HookRegistration::priority));
            return list;
        });
        log.debug("Hook registered: pluginId={} hookName={} priority={}",
                reg.pluginId(), reg.hookName(), reg.priority());
    }

    /**
     * 注销给定插件的所有Hook。
     */
    public void unregisterPlugin(String pluginId) {
        registrations.forEach((hookName, list) ->
                list.removeIf(reg -> reg.pluginId().equals(pluginId)));
    }

    /**
     * 获取某个Hook名称的所有注册，按优先级排序。
     */
    public List<HookRegistration> getHooks(String hookName) {
        return registrations.getOrDefault(hookName, List.of());
    }

    /**
     * 获取所有已注册的Hook名称。
     */
    public Set<String> getHookNames() {
        return Collections.unmodifiableSet(registrations.keySet());
    }

    /**
     * 清除所有注册。
     */
    public void clear() {
        registrations.clear();
    }
}
```

### 1.3.7 AgentInvocationHandler — Hook分发更新

现有的 `AgentInvocationHandler` 更新为在生命周期的正确节点分发新的Hook：

```java
// AgentInvocationHandler.invoke() 内部 — Hook分发新增内容的伪代码:

@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // ... (现有设置: 解析消息, 构建上下文, 创建 AgentContext) ...

    AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
            toolRegistry, method, args, resolvedConfig);

    // 新增: 分发 beforeAgentStart + beforeAgentRun
    dispatch("beforeAgentStart", ctx);
    dispatch("beforeAgentRun", ctx);

    // 新增: 分发 sessionStart（每个会话一次）
    dispatch("sessionStart", ctx.getSessionId(), ctx);

    // 1. beforeRequest hooks（遗留，保留用于向后兼容）
    List<AgentHook> sorted = sortedHooks();
    for (AgentHook hook : sorted) {
        hook.beforeRequest(ctx);
    }

    // ... (现有阶段Pipeline或ReAct执行) ...

    // 在ReAct循环内，围绕每次模型调用:

    // 新增: beforeModelResolve
    dispatch("beforeModelResolve", ctx);

    // 新增: modelCallStarted
    dispatch("modelCallStarted", ctx);

    // 遗留: beforeModel（保留）
    for (AgentHook hook : sorted) {
        messages = hook.beforeModel(messages, ctx);
    }

    // 新增: llmInput
    dispatch("llmInput", assembledPrompt, ctx);

    // ... (实际LLM调用) ...

    // 新增: llmOutput
    dispatch("llmOutput", response, ctx);

    // 遗留: afterModel（保留）
    for (AgentHook hook : sorted) {
        response = hook.afterModel(response, ctx);
    }

    // 新增: modelCallEnded
    dispatch("modelCallEnded", ctx);

    // 围绕ReAct循环中每次工具调用:

    // 新增: beforeToolCall
    dispatch("beforeToolCall", toolName, toolCallId, argsJson, ctx);

    // ... (实际工具执行) ...

    // 新增: afterToolCall
    dispatch("afterToolCall", toolName, toolCallId, result, ctx);

    // 新增: toolResultPersist
    dispatch("toolResultPersist", toolName, result, ctx);

    // ReAct循环结束后（返回结果之前）:

    // 新增: beforeAgentFinalize — 允许REVISE门控
    AgentFinalizeResult finalizeResult = dispatchFinalize(ctx);
    if (finalizeResult.isRevise()) {
        // 使用 retryInstruction 重新循环到ReAct
    }

    // 遗留: afterResult（保留，逆序）
    for (int i = sorted.size() - 1; i >= 0; i--) {
        result = sorted.get(i).afterResult(result, ctx);
    }

    // 新增: agentEnd
    dispatch("agentEnd", ctx);

    // 新增: sessionEnd（如果会话正在结束）
    dispatch("sessionEnd", ctx.getSessionId(), ctx);

    return result;
}
```

AgentInvocationHandler中使用的分发辅助方法：

```java
// 根据Hook名称通用分发 — 对新增Hook使用HookRegistry，
// 对遗留SPI方法使用直接AgentHook调用。

private void dispatch(String hookName, Object... args) {
    List<HookRegistration> hooks = hookRegistry.getHooks(hookName);
    for (HookRegistration reg : hooks) {
        try {
            // 调用处理器（类型安全分发）
            invokeHandler(reg, args);
        } catch (Exception e) {
            log.warn("Hook {} (plugin={}) failed: {}", hookName, reg.pluginId(), e.getMessage());
            // Hook失败默认是非致命的；SecurityHook可抛异常来阻止
        }
    }
}

private AgentFinalizeResult dispatchFinalize(AgentContext ctx) {
    List<HookRegistration> hooks = hookRegistry.getHooks("beforeAgentFinalize");
    for (HookRegistration reg : hooks) {
        try {
            @SuppressWarnings("unchecked")
            Function<AgentContext, AgentFinalizeResult> handler =
                    (Function<AgentContext, AgentFinalizeResult>) reg.handler();
            AgentFinalizeResult result = handler.apply(ctx);
            if (result.isRevise() || result.isFinalize()) {
                return result; // 第一个非CONTINUE立即短路返回
            }
        } catch (Exception e) {
            log.warn("Finalize hook {} (plugin={}) failed: {}",
                    reg.hookName(), reg.pluginId(), e.getMessage());
        }
    }
    return AgentFinalizeResult.continue_();
}
```

### 1.3.8 示例：迁移现有Hook

现有的Hook如 `SecurityCheckHook`、`ApprovalHook`、`OutputGuardHook`、`PlanningHook`、`SandboxHook` 继续实现 `AgentHook`，行为完全相同。针对特定生命周期节点的新Hook通过 `HookRegistry` 注册：

```java
package com.example.hooks;

import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.HookRegistration;
import lyjew.com.lyclaw.react.HookRegistry;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * 示例: 一个压缩日志记录Hook，用于追踪上下文压缩的发生。
 * 通过HookRegistry编程方式注册，而非实现AgentHook。
 */
@Component
public class CompactionLogger {

    private final HookRegistry hookRegistry;

    public CompactionLogger(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    @PostConstruct
    public void registerHooks() {
        hookRegistry.register(HookRegistration.of(
                "compaction-logger",
                "beforeCompaction",
                (java.util.function.Consumer<AgentContext>) ctx -> {
                    // 在压缩之前记录上下文大小
                },
                200
        ));

        hookRegistry.register(HookRegistration.of(
                "compaction-logger",
                "afterCompaction",
                (java.util.function.Consumer<AgentContext>) ctx -> {
                    // 在压缩之后记录上下文大小
                },
                200
        ));
    }
}
```

---

## 1.4 AgentRuntime 模式

### 1.4.1 问题

LyClaw当前仅支持EMBEDDED模式（内置ReAct引擎）。OpenClaw支持ACP（Agent Communication Protocol）模式，其中Agent后端在外部进程（如Node.js Codex CLI实例）中运行，并通过双向协议进行通信。添加ACP支持需要一个清晰的抽象。

### 1.4.2 AgentRuntimeType 枚举

```java
package lyjew.com.lyclaw.react;

/**
 * 支持Agent调用的运行时模式。
 */
public enum AgentRuntimeType {

    /**
     * 默认模式 — LyClaw的内置ReAct引擎在内部处理
     * 完整的推理-行动循环。
     */
    EMBEDDED,

    /**
     * Agent Communication Protocol模式 — Agent后端在外部进程中运行。
     * LyClaw通过双向协议（事件、回合、会话）与其通信。
     */
    ACP
}
```

### 1.4.3 AcpRuntime 接口

```java
package lyjew.com.lyclaw.react;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

/**
 * ACP (Agent Communication Protocol) 运行时SPI。
 *
 * <p>实现管理外部Agent后端（如Codex CLI、自定义Agent服务器）的会话和回合。
 * 协议为:
 * <ol>
 *   <li>{@link #ensureSession(AcpRuntimeEnsureInput)} — 获取或创建会话</li>
 *   <li>{@link #startTurn(AcpRuntimeTurnInput)} — 开始一个对话回合，
 *       接收事件流（文本增量、工具调用、状态更新）</li>
 *   <li>{@link #cancel(AcpRuntimeHandle, String)} — 取消正在运行的回合</li>
 *   <li>{@link #close(AcpRuntimeHandle, String)} — 销毁会话</li>
 * </ol>
 */
public interface AcpRuntime {

    /**
     * 确保给定Agent + 会话键存在一个会话。
     * 返回可用于后续turn/cancel/close调用的句柄。
     */
    Mono<AcpRuntimeHandle> ensureSession(AcpRuntimeEnsureInput input);

    /**
     * 开始一个对话回合。返回 AcpRuntimeEvent 的 Flux:
     * text_delta（流式令牌）, tool_call, tool_result, status, done, error。
     */
    Flux<AcpRuntimeEvent> startTurn(AcpRuntimeTurnInput input);

    /**
     * 查询后端的能（模型、工具、特性）。
     */
    Mono<AcpRuntimeCapabilities> getCapabilities(AcpRuntimeHandle handle);

    /**
     * 取消正在进行的回合。
     */
    Mono<Void> cancel(AcpRuntimeHandle handle, String reason);

    /**
     * 关闭（销毁）一个会话。
     */
    Mono<Void> close(AcpRuntimeHandle handle, String reason);
}
```

### 1.4.4 AcpRuntimeHandle

```java
package lyjew.com.lyclaw.react;

/**
 * 指向活跃ACP会话的不透明句柄。
 *
 * <p>包含AcpRuntime实现所需的标识符，用于将
 * 后续turn/cancel/close请求路由到正确的后端会话。
 */
public class AcpRuntimeHandle {

    /** 会话创建时使用的会话键。 */
    private final String sessionKey;

    /** 此会话所在的后端（如 "codex-cli", "custom-agent-server"）。 */
    private final String backend;

    /** 运行时级别会话名称（可能与面向用户的会话键不同）。 */
    private final String runtimeSessionName;

    /** 此会话的工作目录。 */
    private final String cwd;

    /** 后端特定的会话标识符（如进程PID或UUID）。 */
    private final String backendSessionId;

    /** LyClaw级别的Agent会话标识符。 */
    private final String agentSessionId;

    public AcpRuntimeHandle(String sessionKey, String backend, String runtimeSessionName,
                            String cwd, String backendSessionId, String agentSessionId) {
        this.sessionKey = sessionKey;
        this.backend = backend;
        this.runtimeSessionName = runtimeSessionName;
        this.cwd = cwd;
        this.backendSessionId = backendSessionId;
        this.agentSessionId = agentSessionId;
    }

    public String getSessionKey() { return sessionKey; }
    public String getBackend() { return backend; }
    public String getRuntimeSessionName() { return runtimeSessionName; }
    public String getCwd() { return cwd; }
    public String getBackendSessionId() { return backendSessionId; }
    public String getAgentSessionId() { return agentSessionId; }
}
```

### 1.4.5 AcpRuntimeEvent

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * ACP回合期间发出的事件。
 *
 * <p>事件通过 {@link AcpRuntime#startTurn(AcpRuntimeTurnInput)} 以 Flux 形式流式传输。
 */
public class AcpRuntimeEvent {

    public enum EventType {
        /** 文本内容增量（流式令牌）。 */
        TEXT_DELTA,
        /** 后端想要调用工具。 */
        TOOL_CALL,
        /** 发送回后端的工具结果。 */
        TOOL_RESULT,
        /** 状态更新（如 "thinking", "executing tool"）。 */
        STATUS,
        /** 回合成功完成。 */
        DONE,
        /** 回合失败，带有错误。 */
        ERROR
    }

    private final EventType type;
    private final String data;
    private final Map<String, Object> metadata;

    public AcpRuntimeEvent(EventType type, String data, Map<String, Object> metadata) {
        this.type = type;
        this.data = data;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    // ===== 工厂方法 =====

    public static AcpRuntimeEvent textDelta(String text) {
        return new AcpRuntimeEvent(EventType.TEXT_DELTA, text, null);
    }

    public static AcpRuntimeEvent toolCall(String toolName, String toolCallId,
                                            String arguments, Map<String, Object> metadata) {
        return new AcpRuntimeEvent(EventType.TOOL_CALL,
                toolName,  // data携带工具名称; metadata包含id和参数
                Map.of("toolCallId", toolCallId, "arguments", arguments));
    }

    public static AcpRuntimeEvent toolResult(String toolCallId, String result, boolean success) {
        return new AcpRuntimeEvent(EventType.TOOL_RESULT, result,
                Map.of("toolCallId", toolCallId, "success", success));
    }

    public static AcpRuntimeEvent status(String status) {
        return new AcpRuntimeEvent(EventType.STATUS, status, null);
    }

    public static AcpRuntimeEvent done(String stopReason) {
        return new AcpRuntimeEvent(EventType.DONE, null,
                Map.of("stopReason", stopReason));
    }

    public static AcpRuntimeEvent error(String errorMessage) {
        return new AcpRuntimeEvent(EventType.ERROR, errorMessage, null);
    }

    // ===== Getters =====

    public EventType getType() { return type; }
    public String getData() { return data; }
    public Map<String, Object> getMetadata() { return metadata; }

    public boolean isTextDelta() { return type == EventType.TEXT_DELTA; }
    public boolean isToolCall() { return type == EventType.TOOL_CALL; }
    public boolean isDone() { return type == EventType.DONE; }
    public boolean isError() { return type == EventType.ERROR; }
}
```

### 1.4.6 支持类型

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * {@link AcpRuntime#ensureSession(AcpRuntimeEnsureInput)} 的输入。
 */
public class AcpRuntimeEnsureInput {
    private String agentId;
    private String sessionKey;
    private String backend;        // 使用哪个后端实现
    private String workspaceDir;
    private Map<String, Object> env;
    private Map<String, Object> extra;  // 后端特定的选项

    // 为简洁省略构造函数、getter、setter
    public AcpRuntimeEnsureInput() {}

    public String getAgentId() { return agentId; }
    public void setAgentId(String v) { this.agentId = v; }
    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String v) { this.sessionKey = v; }
    public String getBackend() { return backend; }
    public void setBackend(String v) { this.backend = v; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String v) { this.workspaceDir = v; }
    public Map<String, Object> getEnv() { return env; }
    public void setEnv(Map<String, Object> v) { this.env = v; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> v) { this.extra = v; }
}
```

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * {@link AcpRuntime#startTurn(AcpRuntimeTurnInput)} 的输入。
 */
public class AcpRuntimeTurnInput {
    private AcpRuntimeHandle handle;
    private String userMessage;
    private String systemPrompt;
    private Map<String, Object> context;  // 附加上下文

    // getters/setters
    public AcpRuntimeHandle getHandle() { return handle; }
    public void setHandle(AcpRuntimeHandle v) { this.handle = v; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String v) { this.userMessage = v; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String v) { this.systemPrompt = v; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> v) { this.context = v; }
}
```

```java
package lyjew.com.lyclaw.react;

import java.util.List;
import java.util.Map;

/**
 * ACP运行时报告的后端能力。
 */
public class AcpRuntimeCapabilities {
    private String modelProvider;
    private String modelName;
    private List<String> availableTools;
    private Map<String, Object> features;  // 任意特性标志

    // getters/setters
    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String v) { this.modelProvider = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { this.modelName = v; }
    public List<String> getAvailableTools() { return availableTools; }
    public void setAvailableTools(List<String> v) { this.availableTools = v; }
    public Map<String, Object> getFeatures() { return features; }
    public void setFeatures(Map<String, Object> v) { this.features = v; }
}
```

```java
package lyjew.com.lyclaw.react;

/**
 * 完成的ACP回结果。
 */
public class AcpRuntimeTurnResult {

    public enum Status {
        COMPLETED,   // 回合正常完成
        CANCELLED,   // 回合被用户或系统取消
        FAILED       // 回合失败，带有错误
    }

    private final Status status;
    private final String stopReason;
    private final String error;
    private final String fullText;  // 累积的文本输出

    public AcpRuntimeTurnResult(Status status, String stopReason, String error, String fullText) {
        this.status = status;
        this.stopReason = stopReason;
        this.error = error;
        this.fullText = fullText;
    }

    public Status getStatus() { return status; }
    public String getStopReason() { return stopReason; }
    public String getError() { return error; }
    public String getFullText() { return fullText; }
}
```

---

## 1.5 AgentProxyFactory 重构

### 1.5.1 问题

当前 `AgentProxyFactory` 使用了层层叠加的构造函数链（5个构造函数），将 `modelOverride`/`providerOverride` 硬编码为扁平字符串。它缺乏对 `AgentDefaultsConfig` 的感知，不产生 `ResolvedAgentConfig`，也没有运行时类型选择的概念。

### 1.5.2 重构后的 AgentProxyFactory

```java
package lyjew.com.lyclaw.react;

import java.lang.reflect.Proxy;
import java.util.List;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * Agent代理工厂 — 为 @Agent 接口创建JDK动态代理。
 *
 * <h3>第一阶段变更:</h3>
 * <ul>
 *   <li>构造函数中接受 {@link AgentDefaultsConfig}</li>
 *   <li>{@code create(Class)} 读取 @Agent 注解 → 针对默认值解析
 *       → 生成 {@link ResolvedAgentConfig}</li>
 *   <li>将 ResolvedAgentConfig 传递给 AgentInvocationHandler</li>
 *   <li>支持创建不同运行时类型（EMBEDDED vs ACP）的Agent代理</li>
 * </ul>
 */
public class AgentProxyFactory {

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final AgentConfigResolver configResolver;
    private final String defaultSystemPrompt;
    private final List<AgentHook> hooks;
    private final List<ReactivePipelineStage> stages;
    private final HookRegistry hookRegistry;

    /**
     * 主构造函数 — 接受完整的依赖集。
     *
     * @param chatFacade        用于LLM调用的Chat门面
     * @param reActEngine       用于EMBEDDED运行时的ReAct引擎
     * @param toolRegistry      工具注册表
     * @param configResolver    已加载默认值的Agent配置解析器
     * @param defaultSystemPrompt 未指定时的回退系统提示词
     * @param hooks             全局Agent Hook（应用于所有Agent）
     * @param stages            Pipeline阶段
     * @param hookRegistry      用于新式Hook分发的Hook注册表
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry,
                             AgentConfigResolver configResolver,
                             String defaultSystemPrompt,
                             List<AgentHook> hooks,
                             List<ReactivePipelineStage> stages,
                             HookRegistry hookRegistry) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.configResolver = configResolver;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.hooks = hooks != null ? List.copyOf(hooks) : List.of();
        this.stages = stages != null ? List.copyOf(stages) : List.of();
        this.hookRegistry = hookRegistry;
    }

    /**
     * 向后兼容的构造函数 — 无独立的配置解析器。
     * 从提供的默认值创建内联解析器。
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry,
                             AgentDefaultsConfig defaults,
                             String defaultSystemPrompt,
                             List<AgentHook> hooks,
                             List<ReactivePipelineStage> stages) {
        this(chatFacade, reActEngine, toolRegistry,
                new AgentConfigResolver(defaults),
                defaultSystemPrompt, hooks, stages, new HookRegistry());
    }

    /**
     * 最小化的向后兼容构造函数（无默认值、无Hook、无阶段）。
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry) {
        this(chatFacade, reActEngine, toolRegistry,
                new AgentDefaultsConfig(), null, List.of(), List.of());
    }

    /**
     * 为给定的 @Agent 接口创建动态代理。
     *
     * <p>解析流程:
     * <ol>
     *   <li>从接口读取 @Agent 注解</li>
     *   <li>从注解中提取 agentId、model、provider</li>
     *   <li>向configResolver注册Agent（如果尚未注册）</li>
     *   <li>resolveAgentConfig(agentId) → ResolvedAgentConfig</li>
     *   <li>使用解析后的model/provider（注解覆盖默认值）</li>
     *   <li>根据解析后的配置或系统属性确定runtimeType</li>
     *   <li>使用ResolvedAgentConfig构建AgentInvocationHandler</li>
     *   <li>返回代理</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> agentInterface) {
        if (chatFacade == null) {
            throw new IllegalStateException("ChatFacade must not be null");
        }

        Agent ann = agentInterface.getAnnotation(Agent.class);

        String agentId = resolveAgentId(agentInterface, ann);

        // 解析系统提示词: 注解覆盖 > 默认值
        String systemPrompt = defaultSystemPrompt;
        if (ann != null && !ann.description().isEmpty() && defaultSystemPrompt == null) {
            systemPrompt = ann.description();
        }
        if (ann != null && !ann.systemPromptOverride().isEmpty()) {
            systemPrompt = ann.systemPromptOverride();
        }

        // 向配置解析器注册Agent并解析完整配置
        if (ann != null) {
            configResolver.registerAgent(agentId, ann);
        }
        ResolvedAgentConfig resolvedConfig = configResolver.resolveAgentConfig(agentId);

        // 模型/提供商: 注解覆盖默认值
        String model = resolvedConfig.getModel();
        String provider = resolvedConfig.getProvider();

        // 确定运行时类型
        AgentContext.AgentRuntimeType runtimeType = resolveRuntimeType(resolvedConfig);

        AgentInvocationHandler handler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry,
                systemPrompt, model, provider,
                hooks, stages, resolvedConfig, hookRegistry, runtimeType);

        return (T) Proxy.newProxyInstance(
                agentInterface.getClassLoader(),
                new Class<?>[]{agentInterface},
                handler);
    }

    /**
     * 创建具有显式运行时类型覆盖的代理。
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> agentInterface, AgentContext.AgentRuntimeType runtimeType) {
        T proxy = create(agentInterface);
        // 处理器存储了runtimeType；我们也可以在创建后通过
        // 处理器的setter来传入
        return proxy;
    }

    // ===== 私有辅助方法 =====

    private String resolveAgentId(Class<?> agentInterface, Agent ann) {
        if (ann != null && !ann.id().isEmpty()) {
            return ann.id();
        }
        if (ann != null && !ann.name().isEmpty()) {
            return ann.name();
        }
        String simpleName = agentInterface.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private AgentContext.AgentRuntimeType resolveRuntimeType(ResolvedAgentConfig config) {
        // 检查系统属性覆盖
        String sysProp = System.getProperty("lyclaw.agent.runtime");
        if ("acp".equalsIgnoreCase(sysProp)) {
            return AgentContext.AgentRuntimeType.ACP;
        }
        // 检查配置扩展
        String extVal = config.getExtensions().get("runtimeType");
        if ("acp".equalsIgnoreCase(extVal)) {
            return AgentContext.AgentRuntimeType.ACP;
        }
        return AgentContext.AgentRuntimeType.EMBEDDED;
    }
}
```

### 1.5.3 更新后的 AgentInterfaceProcessor（FactoryBean）

`AgentInterfaceProcessor` 中的 `AgentProxyFactoryBean` 内部类需要小幅更新，以解析 `AgentProxyFactory` bean并调用新的 `create()` 签名：

```java
// AgentInterfaceProcessor.AgentProxyFactoryBean 内部:

@Override
public Object getObject() {
    DefaultListableBeanFactory registry =
            (DefaultListableBeanFactory) LazyBeanFactoryHolder.getBeanFactory();
    if (registry == null) {
        throw new IllegalStateException(
                "BeanFactory not available for @Agent proxy: " + agentInterface.getName());
    }
    AgentProxyFactory factory = registry.getBean(AgentProxyFactory.class);

    // 第一阶段变更: create() 现在内部解析配置并将其传递给处理器
    Object proxy = factory.create(agentInterface);

    String beanName = resolveBeanName();
    registry.destroySingleton(beanName);
    registry.registerSingleton(beanName, proxy);
    return proxy;
}
```

### 1.5.4 更新后的自动配置

```java
package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import lyjew.com.lyclaw.autoconfigure.processor.AgentInterfaceProcessor;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.AgentProxyFactory;
import lyjew.com.lyclaw.react.HookRegistry;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.tool.ToolRegistry;

@AutoConfiguration
@AutoConfigureAfter({ChatAutoConfiguration.class, ReActAutoConfiguration.class, ToolAutoConfiguration.class})
@ConditionalOnClass({ReActEngine.class, ToolRegistry.class, ChatFacade.class})
@EnableConfigurationProperties(AgentDefaultsConfig.class)
public class AgentProxyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentConfigResolver.class)
    public AgentConfigResolver agentConfigResolver(AgentDefaultsConfig defaults) {
        return new AgentConfigResolver(defaults);
    }

    @Bean
    @ConditionalOnMissingBean(HookRegistry.class)
    public HookRegistry hookRegistry() {
        return new HookRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(AgentProxyFactory.class)
    public AgentProxyFactory agentProxyFactory(
            ChatFacade chatFacade,
            ReActEngine reActEngine,
            ToolRegistry toolRegistry,
            AgentConfigResolver configResolver,
            HookRegistry hookRegistry,
            List<AgentHook> hooks,
            List<ReactivePipelineStage> stages) {
        List<AgentHook> hookList = hooks != null ? hooks : List.of();
        List<ReactivePipelineStage> pipelineStages = stages != null ? stages : List.of();
        return new AgentProxyFactory(chatFacade, reActEngine, toolRegistry,
                configResolver, null, hookList, pipelineStages, hookRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(AgentInterfaceProcessor.class)
    public static AgentInterfaceProcessor agentInterfaceProcessor() {
        return new AgentInterfaceProcessor();
    }
}
```

---

## 总结：第一阶段交付物

| # | 组件 | 变更 | 影响 |
|---|---|---|---|
| 1.1a | `@Agent` 注解 | 6个 → 约30个字段 | 类型化、可发现的Agent配置 |
| 1.1b | `AgentDefaultsConfig` | 新类 | 来自 `application.yml` 的全局默认值 |
| 1.1c | `AgentSystemDefaults` | 新类 | 硬编码回退常量 |
| 1.1d | `ResolvedAgentConfig` | 新的不可变类 | 3层深度合并的输出 |
| 1.1e | `AgentConfigResolver` | 增强 | resolveAgentConfig、listAgentIds、工作区目录 |
| 1.2 | `AgentContext` | +15个新字段 + 增强的快照/恢复 | 丰富的运行时数据总线 |
| 1.3a | `AgentHook` | 5个 → 36个方法 | 完整的生命周期覆盖 |
| 1.3b | `AgentFinalizeResult` | 新类 | CONTINUE/REVISE/FINALIZE门控 |
| 1.3c | `HookDecision` | 新类 | PASS/BLOCK 附带原因和元数据 |
| 1.3d | `HookRegistration` | 新 record | 类型化的Hook注册表条目 |
| 1.3e | `HookRegistry` | 新类 | 注册、分发、注销Hook |
| 1.4a | `AgentRuntimeType` | 新枚举 | EMBEDDED / ACP |
| 1.4b | `AcpRuntime` | 新接口 | ensureSession、startTurn、cancel、close |
| 1.4c | `AcpRuntimeHandle/Event/...` | 新类型 | ACP协议数据对象 |
| 1.5 | `AgentProxyFactory` | 重构 | 配置感知、运行时类型支持 |

### 向后兼容性

- 现有 `@Agent` 注解字段（`name`、`description`、`version`、`model`、`provider`、`extensions`）保持不变 — 所有新字段都有合理的默认值。
- 现有 `AgentHook` 方法保持原样 — 新方法均为 `default`（无操作）。
- `AgentContext` 构造函数重载保持了旧签名，同时也提供了接受 `ResolvedAgentConfig` 的新签名。
- `AgentProxyFactory` 保留了向后兼容的构造函数。
- `LyClawAgent.Builder` 在非Spring环境中继续正常工作。
