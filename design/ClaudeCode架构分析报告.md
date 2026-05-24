# Claude Code 社区仓库架构分析报告

> 分析对象：`/home/lyjew/Documents/github/claude-code/` — Anthropic 官方 Claude Code 社区生态仓库
> 分析日期：2026-05-22
> 分析重点：Agent编排调度机制、记忆/上下文系统设计、插件系统架构、Hook事件系统
> 总文件数：220个（排除.git），涵盖14个插件、12个GitHub Actions工作流、4个自定义命令

---

## 一、仓库性质与范围

### 1.1 这是什么

本仓库是 Claude Code 的**社区插件与生态工具仓库**，而非 Claude Code 核心产品的完整源代码。它包含：

- **14个官方插件**：覆盖功能开发、代码审查、PR审查、Git操作、Hook管理、前端设计、SDK开发、模型迁移、输出风格控制等多个领域
- **12个 GitHub Actions 工作流**：实现 @claude 触发、Issue自动分类、去重检测、生命周期管理、自动关闭、统计日志等自动化
- **4个自定义命令**（`.claude/commands/`）：Issue triage、Issue dedupe、Commit-push-PR
- **插件开发SDK**（`plugin-dev`）：完整的插件开发工具包，包含7项技能的开发指南（Agent、Command、Hook、MCP、Plugin Structure、Plugin Settings、Skill）
- **MDM配置示例**（`examples/mdm/`）：macOS mobileconfig/plist + Windows ADMX/ADML/PowerShell 企业级管理
- **Settings示例**（`examples/settings/`）：bash-sandbox、strict、lax 三种安全级别JSON配置
- **Hook示例**（`examples/hooks/`）：bash_command_validator 示例Hook脚本

### 1.2 核心价值

这个仓库最重要的价值不在于"泄漏的源码"，而在于它**完整展示了 Claude Code 的插件生态架构和 Agent 编排思想**：
- 插件如何定义、分发、安装（marketplace.json + plugin.json）
- Agent 如何通过 Markdown frontmatter 声明式定义
- 多 Agent 如何通过 Command（Slash Command）编排协作
- Hook 系统如何在工具执行前后拦截和干预（4种Hook事件类型 + JSON协议）
- Claude Code Action 如何在 CI/CD 中实现 Agent 驱动的自动化
- Agent Skills 系统如何实现可复用的知识注入

### 1.3 关键缺失

以下核心组件在本仓库中**不可见**（属于闭源产品代码）：
- ReAct 循环引擎实现（Thought → Action → Observation 循环）
- Tool 执行沙箱与权限系统（Bash sandbox的实际实现）
- ContextEngine（上下文组装/压缩/引导的核心逻辑）
- Session 管理与持久化（多会话状态管理）
- MCP Server 通信层（协议实现）
- 模型路由与 fallback 逻辑（多模型调度）
- Agent 实际启动/执行的运行时（`Task` tool的实现）
- Plugin 加载/卸载的生命周期管理

### 1.4 仓库完整文件树

```
claude-code/
├── README.md, CHANGELOG.md, LICENSE.md, SECURITY.md
├── feed.xml (77KB Atom发布feed)
├── .claude/commands/           # 项目级自定义命令
│   ├── triage-issue.md         # Issue自动分类
│   ├── dedupe.md               # Issue去重检测
│   └── commit-push-pr.md       # Git提交推送PR
├── .claude-plugin/
│   └── marketplace.json        # 插件市场注册
├── plugins/                    # 14个官方插件
│   ├── feature-dev/            # 7阶段功能开发工作流
│   ├── code-review/            # 自动化PR代码审查
│   ├── pr-review-toolkit/      # 6个专业PR审查Agent
│   ├── commit-commands/        # Git命令快捷操作
│   ├── hookify/                # 可配置Hook规则引擎
│   ├── ralph-wiggum/           # 自引用迭代循环
│   ├── plugin-dev/             # 插件开发SDK
│   ├── frontend-design/        # 前端设计技能
│   ├── security-guidance/      # 安全检查Hook
│   ├── agent-sdk-dev/          # Agent SDK验证
│   ├── explanatory-output-style/
│   ├── learning-output-style/
│   ├── claude-opus-4-5-migration/
│   └── README.md
├── .github/workflows/          # 12个CI/CD工作流
│   ├── claude.yml              # @claude 提及响应
│   ├── claude-issue-triage.yml # Issue自动分类
│   ├── claude-dedupe-issues.yml# Issue自动去重
│   ├── auto-close-duplicates.yml
│   ├── sweep.yml               # 生命周期强制
│   ├── issue-lifecycle-comment.yml
│   ├── remove-autoclose-label.yml
│   ├── lock-closed-issues.yml
│   ├── non-write-users-check.yml
│   ├── issue-opened-dispatch.yml
│   ├── log-issue-events.yml
│   └── backfill-duplicate-comments.yml
├── scripts/                    # 辅助脚本（TS/Bash）
├── examples/                   # MDM+Settings+Hooks示例
└── .devcontainer/              # DevContainer配置
```

---

## 二、插件系统架构

### 2.1 插件标准结构

Claude Code 的插件系统采用约定式目录结构，每个组件有明确的放置位置：

```
plugin-name/
├── .claude-plugin/
│   └── plugin.json          # 插件元数据（name/version/author/description）
├── commands/                # Slash Commands（/command-name 触发）
│   └── command-name.md      # Markdown frontmatter（参数、工具权限） + 指令内容
├── agents/                  # 专业化 Agent 定义
│   └── agent-name.md        # YAML frontmatter（name/model/tools/color） + System Prompt
├── skills/                  # Agent Skills（可复用的知识/能力注入）
│   └── skill-name/
│       ├── SKILL.md         # 技能入口定义
│       ├── references/      # 深度参考资料
│       ├── examples/        # 使用示例和模板
│       └── scripts/         # 辅助验证/测试脚本
├── hooks/                   # 事件钩子
│   ├── hooks.json           # 钩子配置（事件→命令映射 + timeout）
│   └── handler.py/sh        # 钩子处理脚本
├── hooks-handlers/          # 钩子处理程序（替代hooks/目录布局）
├── .mcp.json                # MCP Server 配置（工具扩展）
└── README.md                # 用户文档
```

### 2.2 插件元数据格式

每个插件通过 `.claude-plugin/plugin.json` 声明身份。三个代表性示例：

**简单插件（commit-commands）**：
```json
{
  "name": "commit-commands",
  "description": "Streamline your git workflow with simple commands for committing, pushing, and creating pull requests",
  "version": "1.0.0",
  "author": { "name": "Anthropic", "email": "support@anthropic.com" }
}
```

**复杂插件（hookify）**：
```json
{
  "name": "hookify",
  "version": "0.1.0",
  "description": "Easily create hooks to prevent unwanted behaviors by analyzing conversation patterns",
  "author": { "name": "Daisy Hollman", "email": "daisy@anthropic.com" }
}
```

**SDK插件（plugin-dev）**：包含7个skills、3个agents、1个command，是仓库中最复杂的插件。

**对LyClaw的启示**：LyClaw可以定义类似的 `PluginManifest` 模型。当前 `ToolProvider` SPI 可扩展为 `PluginProvider` SPI：
```java
public interface PluginProvider {
    String getName();
    String getVersion();
    List<AgentDefinition> getAgents();
    List<ToolDefinition> getTools();
    List<HookDefinition> getHooks();
}
```

### 2.3 插件市场机制

仓库中的 `.claude-plugin/marketplace.json` 和 `/plugin` 命令表明 Claude Code 有完整的插件市场生态：
- 插件可以通过 `/plugin` 命令从市场安装
- 支持 `--plugin-dir` 加载本地插件（zip或目录）
- 支持 `--plugin-url` 从URL安装
- `strictKnownMarketplaces` 设置控制可信任的市场来源

**对LyClaw的启示**：可引入 "Agent模板市场" 概念。用户可一键安装预构建的Agent（代码审查员、测试生成器等）。模板市场可以是Git仓库 + JSON索引。

### 2.4 权限与安全控制

从 settings 示例可以推断 Claude Code 的权限模型：

| 设置项 | 作用 | lax | strict | sandbox |
|--------|------|-----|--------|---------|
| `disableBypassPermissionsMode` | 禁止 `--dangerously-skip-permissions` | ✅ | ✅ | |
| `strictKnownMarketplaces` | 限制插件市场来源 | ✅ | ✅ | |
| `allowManagedPermissionRulesOnly` | 仅允许企业管理的权限规则 | | ✅ | ✅ |
| `allowManagedHooksOnly` | 仅允许企业管理的Hook | | ✅ | |
| `permissions.deny` | 工具黑名单（如WebSearch/WebFetch） | | ✅ | |
| `permissions.ask` | 工具需审批（如Bash） | | ✅ | |
| `sandbox.enabled` | Bash必须在沙箱内运行 | | | ✅ |

三层安全级别对应不同的使用场景：
- **lax**：个人开发，允许插件市场，只禁用bypass模式
- **strict**：企业环境，禁止WebSearch/WebFetch，禁止用户自定义权限和Hook，强制审批Bash
- **bash-sandbox**：高安全环境，Bash必须运行在沙箱内，支持网络隔离配置

---

## 三、Agent 定义系统

### 3.1 Agent 声明式定义格式

这是本仓库最核心的发现之一。Claude Code 的 Agent 通过 **YAML frontmatter + Markdown正文** 定义：

```markdown
---
name: code-reviewer
description: Use this agent when you need to review code for adherence to project
  guidelines, style guides, and best practices. This agent should be used proactively
  after writing or modifying code, especially before committing changes or creating
  pull requests. It will check for style violations, potential issues, and ensure
  code follows the established patterns in CLAUDE.md.
  Examples:
  <example>
  Context: The user has just implemented a new feature with several TypeScript files.
  user: "I've added the new authentication feature. Can you check if everything looks good?"
  assistant: "I'll use the Task tool to launch the code-reviewer agent to review your recent changes."
  <commentary>
  Since the user has completed a feature and wants validation, use the code-reviewer agent.
  </commentary>
  </example>
tools: Glob, Grep, LS, Read, NotebookRead, WebFetch, TodoWrite, WebSearch, KillShell, BashOutput
model: sonnet
color: red
---

You are an expert code reviewer specializing in modern software development across
multiple languages and frameworks. Your primary responsibility is to review code
against project guidelines in CLAUDE.md with high precision to minimize false positives.
[... 完整的 System Prompt 内容，通常 500-3000 词 ...]
```

**frontmatter 字段完整分析**：

| 字段 | 类型 | 必需 | 说明 | 示例值 |
|------|------|------|------|--------|
| `name` | string | ✅ | Agent 唯一标识符（kebab-case） | `code-reviewer`, `code-explorer`, `silent-failure-hunter` |
| `description` | string | ✅ | **触发条件描述** — 告诉主Agent何时应启动此Agent。包含 `<example>` XML标签提供few-shot触发场景 | 多段文本含触发场景示例 |
| `tools` | list/string | ❌ | **工具白名单** — 最小权限原则。逗号分隔或JSON数组 | `"Glob, Grep, Read"` 或 `["Write", "Read"]` |
| `model` | string | ❌ | 模型指定（不设则继承父Agent） | `sonnet`, `opus`, `haiku`, `inherit` |
| `color` | string | ❌ | UI展示颜色，语义化标识 | `red`(安全), `green`(生成), `yellow`(分析), `blue`(探索), `cyan`(审查), `magenta`(创建), `pink`(类型) |

### 3.2 Agent 的触发机制

Agent **不通过硬编码的规则匹配触发**，而是通过 `description` 字段中的自然语言 + `<example>` 标签来定义触发条件。**主Agent（Orchestrator）通过语义理解来决定何时启动子Agent**。

这是一个精妙的设计决策：
- **优点**：极其灵活，无需维护复杂的匹配规则，新Agent只需写好描述就能被正确触发
- **缺点**：依赖LLM的语义理解能力，可能漏触发或误触发（这也是为什么需要多个`<example>`提供few-shot示范）

**示例pattern分析**（取自 code-reviewer agent 的 description）：

```xml
<example>
Context: The user has just implemented a new feature with several TypeScript files.
user: "I've added the new authentication feature. Can you check if everything looks good?"
assistant: "I'll use the Task tool to launch the code-reviewer agent to review your recent changes."
<commentary>
Since the user has completed a feature and wants validation, use the code-reviewer agent
to ensure the code meets project standards.
</commentary>
</example>

<example>
Context: The assistant has just written a new utility function.
user: "Please create a function to validate email addresses"
assistant: "Here's the email validation function:" [code...]
assistant: "Now I'll use the Task tool to launch the code-reviewer agent to review this implementation."
<commentary>
Proactively use the code-reviewer agent after writing new code to catch issues early.
</commentary>
</example>

<example>
Context: The user is about to create a PR.
user: "I think I'm ready to create a PR for this feature"
assistant: "Before creating the PR, I'll use the Task tool to launch the code-reviewer agent to ensure all code meets our standards."
<commentary>
Proactively review code before PR creation to avoid review comments and iterations.
</commentary>
</example>
```

每个 `<example>` 包含：
1. **Context**：描述当前场景
2. **user message**：用户可能说的话
3. **assistant response**：Agent应如何响应（包括触发子Agent的决策）
4. **commentary**：解释为什么要在这个场景触发该Agent

**对LyClaw的启发**：
- LyClaw 的 `DelegateToAgentToolProvider` + `AgentRouter` 已经规划了关键词+语义+LLM评分的混合匹配
- 可以借鉴 `<example>` 模式：在 Agent 定义表中存储触发示例，用于 AgentRouter 的语义相似度计算
- AgentRouter 的匹配算法可以参考此模式：提取当前用户意图 → 对每个 Agent 的 description + examples 计算语义相似度 → 选择最佳匹配

### 3.3 Agent 的完整分类体系

从仓库中14个插件的所有 Agent 定义中，可以提取出完整的分类学：

#### 3.3.1 分析型 Agent（Analysis Agents）

**特征**：只读操作，深度分析，输出结构化报告

| Agent | 来源插件 | Model | Tools | 分析领域 |
|-------|---------|-------|-------|---------|
| `code-explorer` | feature-dev | sonnet | Glob,Grep,LS,Read,NotebookRead,WebFetch,TodoWrite,WebSearch,KillShell,BashOutput | 代码执行路径追踪、架构层次映射 |
| `code-reviewer` (feature-dev) | feature-dev | sonnet | 同上 | BUG/安全/质量/规范审查，置信度≥80才报告 |
| `code-reviewer` (pr-review-toolkit) | pr-review-toolkit | opus | 同上 | CLAUDE.md合规 + BUG检测 + 代码质量 |
| `comment-analyzer` | pr-review-toolkit | inherit | 默认集 | 注释准确性 vs 实际代码 |
| `pr-test-analyzer` | pr-review-toolkit | inherit | 默认集 | 行为覆盖（非行覆盖）、边界条件 |
| `silent-failure-hunter` | pr-review-toolkit | inherit | 默认集 | 静默失败、catch块、fallback不透明 |
| `type-design-analyzer` | pr-review-toolkit | inherit | 默认集 | 4维度评分：封装/不变式表达/有用性/执行 |
| `conversation-analyzer` | hookify | inherit | Read,Grep | 对话分析，发现需要Hook化的行为 |

**code-explorer 的标准分析流程**（4步）：
1. **Feature Discovery**：找到入口点（API/UI/CLI/配置）
2. **Code Flow Tracing**：追踪调用链、数据转换、依赖关系
3. **Architecture Analysis**：映射抽象层次（表现层→业务层→数据层）
4. **Implementation Details**：关键算法、错误处理、性能考量

**对LyClaw的启示**：这些分析Agent可以作为 `TemplateAgentFactory` 的预设模板。

#### 3.3.2 生成型 Agent（Generation Agents）

**特征**：读写操作，创建新内容

| Agent | 来源插件 | Model | Tools | 生成内容 |
|-------|---------|-------|-------|---------|
| `code-architect` | feature-dev | sonnet | Glob,Grep,LS,Read,NotebookRead,WebFetch,TodoWrite,WebSearch,KillShell,BashOutput | 完整架构蓝图：模式分析→架构决策→组件设计→实现地图→数据流→构建序列 |
| `code-simplifier` | pr-review-toolkit | opus | 默认集 | 简化代码（保持功能）：减少复杂度、消除冗余、提高可读性 |
| `agent-creator` | plugin-dev | sonnet | Write,Read | Agent配置文件（完整的 frontmatter + System Prompt） |
| `agent-sdk-verifier-py` | agent-sdk-dev | 未指定 | 默认集 | SDK应用验证报告 |
| `agent-sdk-verifier-ts` | agent-sdk-dev | 未指定 | 默认集 | SDK应用验证报告 |

**code-architect 的6步生成流程**：
1. **Codebase Pattern Analysis**：提取已有模式、规范、架构决策
2. **Architecture Design**：基于模式设计完整架构，做出果断选择
3. **Complete Implementation Blueprint**：指定每个文件、组件职责、集成点、数据流
4. **Implementation Map**：具体文件创建/修改清单
5. **Data Flow**：从入口到输出的完整数据流
6. **Build Sequence**：分阶段实施步骤

**对LyClaw的启示**：agent-creator 的模式尤其值得借鉴——Agent能创建Agent。这对应 LyClaw 的 TemplateAgentFactory，用户可以：
1. 描述需要的Agent → AgentRouter 匹配模板 → 生成配置 → 保存到数据库 → DynamicAgentRegistry 注册

#### 3.3.3 验证型 Agent（Validation Agents）

**特征**：检查合规性，输出pass/fail判定

| Agent | 来源插件 | Model | 验证内容 |
|-------|---------|-------|---------|
| `plugin-validator` | plugin-dev | 未指定 | 插件结构完整性、组件兼容性、最佳实践 |
| `skill-reviewer` | plugin-dev | 未指定 | 技能定义质量、完整性、可发现性 |

### 3.4 工具可见性控制的四级策略

从 Agent 定义中的 `tools` 字段可以归纳出四级工具可见性控制：

**Level 1: 默认全部（未指定tools字段）**
- Agent 继承主Agent的完整工具集
- 代表：pr-review-toolkit的大多数Agent、ralph-wiggum无Agent
- 风险：子Agent可能执行危险操作

**Level 2: 只读分析（Glob, Grep, LS, Read）**
- Agent 只能探索和读取，不能修改任何文件
- 代表：code-explorer (feature-dev)
- 安全级别：高

**Level 3: 受限读写（Read + Write）**
- Agent 可以读写文件，但不能执行Bash
- 代表：agent-creator (plugin-dev) — `tools: ["Write", "Read"]`
- 安全级别：中

**Level 4: 全部分析工具（含WebFetch/WebSearch/TodoWrite）**
- Agent 有完整的分析工具集，但无Write权限
- 代表：code-reviewer, code-architect (feature-dev)
- `tools: Glob, Grep, LS, Read, NotebookRead, WebFetch, TodoWrite, WebSearch, KillShell, BashOutput`

**对LyClaw的映射**：

```java
public enum ToolVisibilityLevel {
    FULL,              // Level 1: 继承全部
    READ_ONLY,         // Level 2: Glob, Grep, Read only
    READ_WRITE,        // Level 3: Read + Write
    ANALYSIS           // Level 4: 所有分析工具，无Write/Bash
}

public record AgentDefinition(
    String name,
    ToolVisibilityLevel toolLevel,  // 预定义级别
    List<String> toolAllowlist,     // 精确白名单（覆盖level）
    List<String> toolDenylist       // 精确黑名单
) {}
```

---

## 四、多 Agent 编排模式

### 4.1 Command 驱动的编排（Slash Commands）

这是 Claude Code 最主要的多 Agent 编排方式。以 `/feature-dev` 命令为例，完整的7阶段编排：

```
Phase 1: Discovery (需求发现)
  └─ 澄清功能需求、约束条件 → 确认理解

Phase 2: Codebase Exploration (代码库探索)
  └─ 并行启动 2-3 个 code-explorer Agent:
      ├─ Agent A: "Find features similar to [feature] and trace implementation"
      ├─ Agent B: "Map the architecture and abstractions for [feature area]"
      └─ Agent C: "Analyze current implementation of [related feature]"
  └─ 聚合结果 → 读取所有Agent识别的关键文件

Phase 3: Clarifying Questions (澄清问题)
  └─ 识别模糊点: 边界情况、错误处理、集成点、向后兼容性、性能需求
  └─ ⏸️ 等待用户回答

Phase 4: Architecture Design (架构设计)
  └─ 并行启动 2-3 个 code-architect Agent:
      ├─ Agent A: "Minimal changes approach" (最小改动，最大复用)
      ├─ Agent B: "Clean architecture approach" (可维护性，优雅抽象)
      └─ Agent C: "Pragmatic balance approach" (速度+质量平衡)
  └─ 汇总对比 → 推荐最佳方案 → ⏸️ 等待用户选择

Phase 5: Implementation (实现)
  └─ 读取Phase 2-4识别的所有关键文件
  └─ 按选定架构实现 → 遵循代码库规范

Phase 6: Quality Review (质量审查)
  └─ 并行启动 3 个 code-reviewer Agent:
      ├─ Agent A: "Simplicity/DRY/Elegance" (代码质量和可维护性)
      ├─ Agent B: "Bugs/Functional Correctness" (功能正确性和逻辑错误)
      └─ Agent C: "Conventions/Abstractions" (项目标准和模式)
  └─ 汇总 → 识别最高严重性问题 → ⏸️ 询问用户处理方式

Phase 7: Summary (总结)
  └─ 标记所有todos完成 → 总结构建内容、关键决策、修改文件、建议后续步骤
```

**编排特点**：
1. **阶段式推进**：7个阶段顺序执行，每个阶段有明确的输入/输出
2. **并行子Agent**：在探索(Phase 2)、设计(Phase 4)、审查(Phase 6)阶段启动多个Agent并行工作
3. **人机交互点**：在关键决策点（Phase 3、4、6）等待用户确认（⏸️标记）
4. **上下文传递**：每个阶段产出的结果作为下一阶段的输入
5. **文件系统中介**：Agent将关键文件列表返回给主Agent，主Agent读取这些文件建立深度理解

### 4.2 并行 Agent 审查模式（Redundancy + Validation）

以 `/code-review` 命令的PR审查为例，展示了多Agent质量保证的完整范式：

```
Step 1: 前置检查（Haiku 轻量Agent）
  └─ 检查 PR是否已关闭/草稿/无需审查/Claude已评论
  └─ 任何条件为真 → 停止

Step 2: CLAUDE.md 收集（Haiku Agent）
  └─ 返回所有相关 CLAUDE.md 文件路径列表
      ├─ 根目录 CLAUDE.md
      ├─ 修改文件所在目录的 CLAUDE.md
      └─ 所有父目录的 CLAUDE.md

Step 3: PR 变更摘要（Sonnet Agent）
  └─ 阅读PR diff → 生成变更描述

Step 4: 并行独立审查（4个Agent同时启动）
  ├─ Agent 1+2: CLAUDE.md 合规审计（Sonnet，双份冗余）
  │   └─ 注意：只考虑与被审查文件共享路径的CLAUDE.md规则
  ├─ Agent 3: 明显BUG扫描（Opus，只看diff）
  │   └─ CRITICAL: 只标记高信号问题（编译失败/类型错误/明确逻辑错误）
  └─ Agent 4: 安全/逻辑问题（Opus，关注新代码）
      └─ 只关注变更代码范围内的问题

Step 5: 二级验证（对Agent 3+4发现的每个问题）
  └─ 每个问题启动一个独立验证Agent
      ├─ BUG/逻辑问题 → Opus 验证Agent
      └─ CLAUDE.md违规 → Sonnet 验证Agent
  └─ 验证Agent获取PR标题+描述+问题描述 → 独立验证问题真实性

Step 6: 过滤（只保留验证通过的问题）
  └─ 未被验证的问题 → 丢弃

Step 7: 输出或发布
  ├─ 无 --comment: 终端输出 → 停止
  ├─ --comment + 无问题: 发布 "No issues found" 评论
  └─ --comment + 有问题: 继续Step 8-9

Step 8: 预览评论列表（内部审核，不发布）

Step 9: 发布内联评论
  └─ 使用 mcp__github_inline_comment__create_inline_comment
  └─ 每个评论: confirmer=true + 代码引用 + 建议
  └─ 小修复(≤5行): 包含 committable suggestion
  └─ 大修复(6+行): 只描述问题不包含suggestion
```

**关键设计决策**：
1. **多模型分层**：Haiku（轻量检查）→ Sonnet（常规审查）→ Opus（深度分析），根据任务复杂度选择不同模型
2. **冗余审查**：2个Agent独立审查 CLAUDE.md 合规性，减少遗漏
3. **二级验证**：发现的问题需要独立的验证Agent重新确认，过滤LLM的幻觉/误判
4. **置信度阈值**：只报告评分 ≥80 的问题（0-25=false positive, 26-50=nitpick, 51-75=minor, 76-90=important, 91-100=critical）
5. **False Positive 黑名单**：明确列出不报告的issue类型：
   - 预存在的问题（非PR引入）
   - 看起来像BUG但实际正确的代码
   - Pedantic nitpicks（资深工程师不会提出的问题）
   - Linter会捕获的问题
   - 通用代码质量担忧（除非CLAUDE.md明确要求）
   - CLAUDE.md提到但代码中已显式silence的问题（lint ignore注释）

**对LyClaw的启发**：这是在 LLM 固有不确定性的前提下，通过**冗余+验证+阈值**三层机制提高审查质量的范式。LyClaw的 Phase 4 编排阶段应该内置这些质量控制机制：
```java
public class QualityGate {
    int redundancyFactor;     // 同一任务并行Agent数
    boolean requireValidation; // 是否需要二级验证
    int confidenceThreshold;  // 置信度阈值（0-100）
    List<String> falsePositivePatterns; // 误报排除模式
}
```

### 4.3 自引用迭代循环（Ralph Wiggum 模式）

`ralph-wiggum` 插件实现了一种独特的 Agent 编排模式——通过 Hook 拦截 Stop 事件实现**会话内自引用迭代**。

**传统方案 vs Ralph方案**：
```
传统:  while true; do cat PROMPT.md | claude --continue; done
        └─ 每次迭代启动新进程，丢失上下文

Ralph: Stop Hook 拦截退出 → block决策 → 注入相同prompt → 同一会话继续
        └─ 保持完整上下文，文件系统作为迭代间通信介质
```

**Ralph State File 格式**（`.claude/ralph-loop.local.md`）：

```markdown
---
active: true
iteration: 1
max_iterations: 50
completion_promise: "ALL_TESTS_PASS"
started_at: "2026-05-22T10:30:00Z"
---

Build a REST API for todos with full CRUD operations.
When complete, output <promise>ALL_TESTS_PASS</promise>.
```

**Stop Hook 完整执行流程**（`hooks/stop-hook.sh`，178行bash脚本）：

```
1. 检查状态文件是否存在 → 不存在 = 无活动循环，允许退出
2. 解析 frontmatter 字段（iteration, max_iterations, completion_promise）
3. 校验数值字段（防御性编程，状态文件损坏则清理退出）
4. 检查迭代上限 → 达到 max_iterations → 删除状态文件，允许退出
5. 读取 transcript 文件 → 获取最后一条 assistant 消息
6. 提取文本内容 → 搜索 <promise> 标签 → 比对 completion_promise
7. 匹配成功 → 删除状态文件，允许退出
8. 未匹配 → 增加迭代计数 → 提取原始prompt → 返回 block 决策
```

**Hook 响应的JSON格式**：

```json
// 继续循环（block）
{
  "decision": "block",
  "reason": "Build a REST API for todos with full CRUD operations...",
  "systemMessage": "🔄 Ralph iteration 3 | To stop: output <promise>ALL_TESTS_PASS</promise>"
}

// 完成（allow）
// exit 0 = 不输出任何JSON = 允许退出
```

**关键设计要点**：
1. **状态文件作为迭代状态**：frontmatter存储元数据（迭代计数、上线、完成标记），正文存储prompt
2. **Transcript作为上下文桥梁**：Hook读取完整对话历史，不是只读取最后一条消息
3. **原子文件更新**：`sed` 在临时文件操作 → `mv` 原子替换，避免并发写入损坏
4. **防御性编程**：每个步骤都有错误处理，损坏状态文件自动清理
5. **明确的逃逸机制**：max_iterations + completion_promise 双重保障

**对LyClaw的启示**：这比传统的 while-true 轮询更优雅。LyClaw 的 Pipeline 可以借鉴：
- 在 **Response Stage** 添加 `ContinueCheckHook`：
  ```java
  public interface ContinueCheckHook {
      ContinueDecision check(AgentContext ctx, String lastOutput);
      // 返回 CONTINUE（注入新prompt继续）或 ALLOW_EXIT（允许退出）
  }
  ```
- 状态持久化到 SQLite（替代文件系统的 `.local.md`）
- 前端展示迭代进度条（"Iteration 5/50 — 输出 <promise>DONE</promise> 以完成"）

### 4.4 事件驱动的 Agent 触发（GitHub Actions 集成）

GitHub Actions 工作流展示了 **Agent即CI/CD Runner** 的模式：

```
GitHub Event → Workflow Trigger → Checkout → Claude Code Action → Slash Command → Agent 执行
```

**claude-code-action 的使用模式**：

```yaml
- name: Run Claude Code
  uses: anthropics/claude-code-action@v1
  with:
    anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
    prompt: "/triage-issue REPO: ${{ github.repository }} ISSUE_NUMBER: ${{ github.event.issue.number }}"
    claude_args: "--model claude-sonnet-4-5-20250929"
    github_token: ${{ secrets.GITHUB_TOKEN }}
```

**12个工作流的完整分类**：

| 工作流 | 触发条件 | 模型 | 权限 | 延迟要求 |
|--------|----------|------|------|---------|
| `claude-issue-triage` | Issue打开/评论 | Opus 4.6 | issues:write | 5min timeout |
| `claude-dedupe-issues` | Issue打开 | Sonnet 4.5 | issues:write | 10min timeout |
| `claude` | @claude 提及 | Sonnet 4.5 | read-only | 标准 |
| `auto-close-duplicates` | 定时 9am daily | Bun脚本 | issues:write | 10min timeout |
| `sweep` | 定时 10am+10pm | Bun脚本 | issues:write | 标准 |
| `issue-lifecycle-comment` | Label添加 | Bun脚本 | issues:write | 标准 |
| `remove-autoclose-label` | Issue新评论 | JS脚本 | issues:write | 标准 |
| `lock-closed-issues` | 定时 1pm UTC | JS脚本 | issues:write | 标准 |
| `non-write-users-check` | PR修改.github/ | Bash | pull-requests:write | 标准 |
| `issue-opened-dispatch` | Issue打开 | gh api | issues:read | 1min timeout |
| `log-issue-events` | Issue打开/关闭 | curl→Statsig | issues:read | 标准 |
| `backfill-duplicate-comments` | 手动触发 | Bun脚本 | issues:read | 30min timeout |

**关键区分**：
- **Claude驱动的**（triage, dedupe, @claude）：需要 `anthropic_api_key`，使用 `claude-code-action`
- **脚本驱动的**（sweep, lifecycle-comment, lock-closed, auto-close）：使用 Bun/TypeScript 脚本，不需要LLM调用

**对LyClaw的启示**：LyClaw 可以引入类似的"事件触发Agent"概念：
```java
public enum TriggerType {
    WEBHOOK,      // Webhook事件 → Agent
    CRON,         // 定时任务 → Agent
    GIT_HOOK,     // Git PreCommit/PostCommit → Agent
    MANUAL        // 用户手动触发
}
```

### 4.5 Recall + Precision 双层 Agent 架构

以 `/dedupe` 命令的 Issue 去重流程为例：

```
Step 1: 前置检查（1个Agent）
  └─ 检查 Issue是否已关闭/不需要去重/已有去重评论

Step 2: Issue摘要（1个Agent）
  └─ 阅读Issue内容 → 返回摘要

Step 3: 广度搜索（5个并行Agent — Recall层）
  ├─ Agent 1: 使用关键词组合A搜索
  ├─ Agent 2: 使用关键词组合B搜索
  ├─ Agent 3: 使用语义变体搜索
  ├─ Agent 4: 使用错误消息搜索
  └─ Agent 5: 使用功能领域搜索
  └─ 每个Agent返回候选重复Issue列表

Step 4: 精确过滤（1个Agent — Precision层）
  └─ 输入：原始Issue摘要 + 5个Agent的候选列表
  └─ 过滤误报（看似重复但实际不同的问题）
  └─ 输出：最多3个真正的重复Issue

Step 5: 发布结果
  └─ ./scripts/comment-on-duplicates.sh --potential-duplicates <dup1> <dup2>
```

**这是一种"Recall Agent → Precision Agent"的双层信息检索架构**：
- **Recall层**：多个Agent使用不同策略提高召回率（宁可错杀不可放过）
- **Precision层**：一个Agent对候选结果进行精确过滤（去除误报）

**对LyClaw的映射**：这在代码搜索、文档检索、知识库查询等场景都有应用价值：
```java
// Recall Agents (并行，最大化召回)
List<SearchAgent> recallAgents = spawnParallel(
    new KeywordSearchAgent(query),
    new SemanticSearchAgent(query),
    new CodePatternSearchAgent(query)
);

// Precision Agent (过滤，最大化精确率)
PrecisionFilterAgent filter = spawnAgent(
    new PrecisionFilterAgent(recallResults)
);
```

---

## 五、Hook 系统深度分析

### 5.1 Hook 系统的插件化实现

Hook 系统在 Claude Code 中有两种实现方式：

**方式一：hookify 规则引擎（声明式配置）**
- 用户编写 `.claude/hookify.*.local.md` 规则文件
- YAML frontmatter 定义规则条件（name/enabled/event/pattern/action/conditions）
- Markdown body 定义警告消息
- Python RuleEngine 在运行时加载和评估规则
- 适合非技术用户：不需要写代码，只需写配置

**方式二：直接 Hook 脚本（编程式）**
- 在插件的 `hooks/` 目录放置 `hooks.json` + 处理脚本
- `hooks.json` 声明事件到脚本的映射、超时时间
- 处理脚本直接读取 stdin 的 JSON 输入，输出 JSON 到 stdout
- 适合开发者：完全控制 Hook 逻辑

**两种方式的对比**：

| 维度 | hookify 规则引擎 | 直接 Hook 脚本 |
|------|-----------------|---------------|
| 配置方式 | YAML frontmatter + Markdown | hooks.json + 脚本文件 |
| 编程语言 | Python (固定) | 任意语言 (Bash/Python/Node等) |
| 灵活性 | 6种运算符组合 | 图灵完备 |
| 用户门槛 | 低（写配置） | 中（写脚本） |
| 性能 | 规则评估有开销 | 脚本启动有开销 |
| 典型场景 | 安全检查、代码规范 | 复杂循环控制、外部系统集成 |
| 代表插件 | hookify, security-guidance | ralph-wiggum, explanatory-output-style |

### 5.2 Hook 事件类型与用途

从 `hookify/hooks/hooks.json` 可以看到 Claude Code 支持4种 Hook 事件：

| 事件 | 触发时机 | 输入数据 | 输出能力 | 典型用途 |
|------|----------|---------|---------|---------|
| `PreToolUse` | 工具执行**前** | tool_name, tool_input, hook_event_name | permissionDecision(allow/deny), systemMessage | 安全校验、参数审查、规则拦截 |
| `PostToolUse` | 工具执行**后** | tool_name, tool_input, tool_output, hook_event_name | systemMessage | 结果审核、副作用记录、日志 |
| `Stop` | Agent 尝试退出**时** | reason, transcript_path | decision(block/allow), reason(新prompt), systemMessage | 循环控制、完成条件检查 |
| `UserPromptSubmit` | 用户提交提示词**时** | user_prompt | systemMessage | 提示词预处理、上下文自动增强 |

### 5.3 Hook 执行协议

从 `hookify/hooks/pretooluse.py` 等脚本推断的 Hook 标准协议：

**输入协议（stdin → JSON）**：
```json
{
  "tool_name": "Bash",
  "tool_input": {
    "command": "rm -rf /tmp/test"
  },
  "hook_event_name": "PreToolUse",
  "transcript_path": "/path/to/transcript.jsonl",
  "reason": "Task completed",
  "user_prompt": "帮我删除临时文件"
}
```

**输出协议（stdout → JSON）**：

```json
// PreToolUse / PostToolUse — 拒绝
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "deny"
  },
  "systemMessage": "**[block-dangerous-rm]**\n⚠️ Dangerous rm command detected!"
}

// PreToolUse / PostToolUse — 允许（无匹配规则）
{}

// Stop — 阻止退出
{
  "decision": "block",
  "reason": "继续执行的原prompt文本",
  "systemMessage": "🔄 Iteration 5 | To stop: output <promise>DONE</promise>"
}

// Stop — 允许退出
// exit 0 (不输出JSON或输出空JSON)

// 所有事件 — 仅警告
{
  "systemMessage": "**[warn-console-log]**\n🔍 Console.log detected!"
}
```

**核心设计原则**：
1. **fail-open**：Hook 脚本出错时**不阻断操作**（所有脚本的 finally 块都是 `sys.exit(0)`）
2. **stdin/stdout 协议**：纯 JSON 文本协议，语言无关
3. **timeout 保护**：每个 Hook 配置了独立的 timeout（通常10秒），超时自动允许
4. **事件分类**：根据 tool_name 将工具事件分为 bash / file 两类进行规则过滤

### 5.4 Hookify 规则引擎详解

`hookify` 插件是本仓库中**代码量最大的插件**（~1000行 Python），实现了可配置的 Hook 规则引擎。

**规则文件格式**（`.claude/hookify.{name}.local.md`）：

```markdown
---
name: block-dangerous-rm
enabled: true
event: bash
pattern: rm\s+-rf
action: block
---

⚠️ **Dangerous rm command detected!**

This command could delete important files. Please:
- Verify the path is correct
- Consider using a safer approach
- Make sure you have backups
```

**高级多条件规则**：

```markdown
---
name: warn-api-key-in-ts
enabled: true
event: file
action: warn
conditions:
  - field: file_path
    operator: regex_match
    pattern: \.tsx?$
  - field: new_text
    operator: regex_match
    pattern: (API_KEY|SECRET|TOKEN)\s*=\s*["']
---

🔐 **Hardcoded credential in TypeScript!**

Use environment variables instead of hardcoded values.
```

**RuleEngine 核心类设计**（`core/rule_engine.py`，314行）：

```python
class RuleEngine:
    def evaluate_rules(self, rules, input_data) -> dict:
        # 1. 遍历所有规则 → 分为 blocking_rules 和 warning_rules
        # 2. blocking 优先于 warning
        # 3. 匹配的 blocking 规则 → 根据事件类型返回不同的 block 格式
        #    - Stop → decision:block + reason + systemMessage
        #    - PreToolUse/PostToolUse → permissionDecision:deny + systemMessage
        # 4. 只有 warning → systemMessage 提示
        # 5. 无匹配 → 空JSON = 允许

    def _rule_matches(self, rule, input_data) -> bool:
        # 1. 检查 tool_matcher（工具名白名单，支持 | 分隔的OR匹配）
        # 2. 检查 conditions（ALL 条件必须满足）
        #    - 无 conditions → 不匹配（防御性：规则必须有条件）

    def _check_condition(self, condition, tool_name, tool_input, input_data) -> bool:
        # 1. 从 input_data 中提取 field 值
        # 2. 应用 operator（regex_match/contains/equals/not_contains/starts_with/ends_with）
        # 3. 返回匹配结果

    def _extract_field(self, field, tool_name, tool_input, input_data) -> str:
        # 按工具类型提取不同字段：
        #   Bash: command
        #   Write/Edit: file_path, content, new_string, old_string
        #   MultiEdit: file_path, edits (拼接所有new_string)
        #   Stop: reason, transcript (从transcript_path文件读取)
        #   UserPromptSubmit: user_prompt
```

**6种条件运算符**（全部支持）：

| 运算符 | Python实现 | 示例 | 用途 |
|--------|-----------|------|------|
| `regex_match` | `re.compile(pattern).search(field_value)` | `rm\s+-rf` | 正则模式匹配（支持IGNORECASE） |
| `contains` | `pattern in field_value` | `API_KEY` | 子串包含检查 |
| `equals` | `pattern == field_value` | `.env` | 精确字符串匹配 |
| `not_contains` | `pattern not in field_value` | `npm test` | 排除检查（如Stop事件检查是否运行过测试） |
| `starts_with` | `field_value.startswith(pattern)` | `sudo ` | 前缀检查 |
| `ends_with` | `field_value.endswith(pattern)` | `.env` | 后缀检查（常用于文件扩展名） |

**正则缓存优化**：`@lru_cache(maxsize=128)` 缓存编译后的正则，避免重复编译。

### 5.5 四种Hook事件的实际应用

#### PreToolUse — 安全门禁

```
Bash:  rm -rf /tmp → [Hook检查] → block (deny) + 警告消息
Write: console.log("debug") → [Hook检查] → warn + 提醒消息
Edit:  修改 .env 文件 → [Hook检查] → warn + 敏感文件提醒
```

**典型规则示例**：
```yaml
# block: 阻止危险命令
name: block-chmod-777
event: bash
pattern: chmod\s+777
action: block
---
Don't use chmod 777 — it's a security risk. Use specific permissions.

# warn: 调试代码提醒
name: warn-console-log
event: file
pattern: console\.log\(
action: warn
---
Console.log detected. Remember to remove debug logging before committing.
```

#### PostToolUse — 事后审核

```
Bash执行完成 → [Hook检查] → 检查输出是否包含敏感信息
Write完成     → [Hook检查] → 检查文件内容是否符合规范
```

#### Stop — 完成条件检查

```
Agent: "我完成了，准备退出"
  → [Stop Hook]
    → 检查 transcript 是否包含 npm test 输出
    → 未找到 → block: "请先运行测试"
    → 找到 → allow: 退出

Agent: 输出 "<promise>ALL_TESTS_PASS</promise>"
  → [Stop Hook]
    → 在最后一条消息中找到 completion_promise
    → 比对成功 → allow: 退出
```

**Stop规则的强大之处**：可以用 `not_contains` 运算符检查 transcript 中是否缺少必要的操作：

```yaml
name: require-tests-run
event: stop
action: block
conditions:
  - field: transcript
    operator: not_contains
    pattern: npm test|pytest|cargo test
---
Tests not detected in transcript! Before stopping, please run tests.
```

#### UserPromptSubmit — 上下文增强

```
用户: "帮我部署到生产环境"
  → [UserPromptSubmit Hook]
    → 检测到 "deploy to production"
    → 注入提醒: "生产部署检查清单: 测试通过? 团队审查? 监控就绪?"
```

### 5.6 Hooks 对 LyClaw Pipeline 的映射

Claude Code 的4种 Hook 事件完美对应 LyClaw 的 5 Stage Pipeline：

| Claude Code Hook | LyClaw Pipeline Stage | 触发时机 | 可拦截的操作 |
|------------------|----------------------|----------|-------------|
| `UserPromptSubmit` | **Pipeline 入口** (PreTool之前) | 用户提交消息 | 提示词增强、上下文注入 |
| `PreToolUse` | **PreTool Stage** | 工具执行前 | 参数校验、权限检查、规则拦截 |
| `PostToolUse` | **PostTool Stage** | 工具执行后 | 结果审核、副作用记录 |
| `Stop` | **Response Stage** | Agent准备返回 | 完成条件检查、迭代控制 |

**建议的 Hook 接口设计**：

```java
public sealed interface HookResult {
    record Allow() implements HookResult {}
    record Warn(String systemMessage) implements HookResult {}
    record Deny(String systemMessage) implements HookResult {}
    record BlockAndContinue(String reason, String systemMessage) implements HookResult {}
}

public interface PipelineHook {
    HookResult onPreToolUse(String toolName, Map<String, Object> toolInput);
    HookResult onPostToolUse(String toolName, Map<String, Object> toolOutput);
    HookResult onBeforeResponse(String lastOutput, String transcriptPath);
    HookResult onUserPrompt(String userPrompt);
}
```

### 5.7 Hook 脚本的详细执行流程

以 `pretooluse.py` 为例，完整的 Hook 执行生命周期：

```
┌─────────────────────────────────────────────────────────┐
│ 1. Agent 准备调用工具 (如 Bash: "rm -rf /tmp/test")     │
│ 2. Claude Code 构建 Hook 输入 JSON                       │
│ 3. 通过 stdin 传递给 pretooluse.py                       │
│ 4. pretooluse.py:                                        │
│    a. sys.stdin.read() → 解析 JSON                       │
│    b. 提取 tool_name → 分类为 bash/file/other            │
│    c. 从 .claude/hookify.*.local.md 加载规则             │
│    d. 按 event 类型过滤规则 (bash → bash规则)             │
│    e. 遍历规则: _rule_matches(rule, input_data)          │
│       - 检查 tool_matcher (工具名白名单)                 │
│       - 检查每个 condition                               │
│         · _extract_field(field, ...) → 提取字段值        │
│         · _check_condition(...) → 应用运算符             │
│       - 所有 conditions 满足 → 规则匹配                  │
│    f. 收集匹配的规则 → 分为 blocking 和 warning          │
│    g. blocking 优先: 输出 deny + systemMessage           │
│    h. 只有 warning: 输出空decision + systemMessage       │
│    i. 无匹配: 输出 {} (空JSON = allow)                   │
│ 5. Claude Code 读取 stdout → 解析响应                    │
│ 6. permissionDecision=deny → 阻止工具调用，显示消息       │
│ 7. 空响应/warn → 允许工具调用，显示警告消息               │
│ 8. 任何异常 → sys.exit(0) → allow (fail-open)            │
└─────────────────────────────────────────────────────────┘
```

**pretooluse.py 核心代码逻辑**（简化还原）：

```python
def main():
    try:
        # 1. 读取 Hook 输入
        hook_input = json.loads(sys.stdin.read())
        tool_name = hook_input.get('tool_name', '')
        tool_input = hook_input.get('tool_input', {})

        # 2. 确定事件类型 (用于规则过滤)
        if tool_name == 'Bash':
            event_type = 'bash'
            field_value = tool_input.get('command', '')
        elif tool_name in ('Write', 'Edit', 'MultiEdit'):
            event_type = 'file'
            field_value = tool_input.get('file_path', '')
        else:
            event_type = 'other'
            field_value = ''

        # 3. 加载并过滤规则
        rules = load_rules()  # 从 .claude/hookify.*.local.md 加载
        matching_rules = [r for r in rules if r.get('event') == event_type]

        # 4. 评估规则
        engine = RuleEngine()
        result = engine.evaluate_rules(matching_rules, hook_input)

        # 5. 输出结果
        if result:
            print(json.dumps(result))

    except Exception as e:
        # fail-open: 任何异常都不阻断操作
        print(json.dumps({"systemMessage": f"Hook error: {e}"}), file=sys.stderr)

    finally:
        sys.exit(0)  # 总是 exit 0
```

**stop.py 的特殊处理逻辑**：

```python
def main():
    try:
        hook_input = json.loads(sys.stdin.read())
        reason = hook_input.get('reason', '')
        transcript_path = hook_input.get('transcript_path', '')

        # 读取 transcript 文件内容作为检查目标
        transcript_content = ''
        if transcript_path and os.path.exists(transcript_path):
            with open(transcript_path, 'r') as f:
                transcript_content = f.read()

        # 将 transcript 内容注入到 input_data
        hook_input['transcript'] = transcript_content

        # 加载 stop 类型规则并评估
        rules = load_rules()
        stop_rules = [r for r in rules if r.get('event') == 'stop']

        engine = RuleEngine()
        result = engine.evaluate_rules(stop_rules, hook_input)

        if result:
            # Stop 事件的 block 响应格式不同
            # 包含 decision: "block" 和新的 reason (prompt)
            print(json.dumps(result))

    except Exception as e:
        pass
    finally:
        sys.exit(0)
```

**对 LyClaw 的实现指导**：
- Hook 输入/输出协议必须版本化（如 `"protocol_version": "1.0"`）
- Hook 脚本超时后应有降级策略（允许/拒绝/重试）
- 建议使用 WebAssembly 沙箱运行用户提供的 Hook 脚本
- Hook 规则的热加载：监听 `.local.md` 文件变化，自动重新编译正则

---

## 六、记忆与上下文系统

### 6.1 上下文传递的分层架构

虽然 ContextEngine 的核心实现在此仓库中不可见，但从插件设计和 Command 定义可以推断其上下文传递策略：

```
Layer 1: 全局上下文 (CLAUDE.md)
  ├─ 根目录 CLAUDE.md            → 项目级规范（编码风格、架构原则）
  ├─ 子目录 CLAUDE.md            → 模块级规范（API约定、测试策略）
  └─ 功能目录 CLAUDE.md          → 功能级规范（业务规则、集成要求）

Layer 2: Agent上下文 (System Prompt)
  ├─ Agent role & expertise       → Agent身份定义
  ├─ Core responsibilities        → 核心职责
  ├─ Process steps                → 执行流程
  └─ Output format                → 输出格式

Layer 3: Skill上下文 (SKILL.md)
  ├─ SKILL.md                     → 技能概要
  ├─ references/                  → 深度参考
  └─ examples/                    → few-shot示例

Layer 4: 会话上下文 (Transcript)
  ├─ 对话历史                     → JSONL格式
  ├─ 工具调用记录                  → tool_use + tool_result
  └─ Session metadata             → sessionId, agentId, model

Layer 5: 临时状态 (.local.md)
  ├─ ralph-loop.local.md          → 迭代循环状态
  └─ hookify.*.local.md           → Hook规则定义
```

### 6.2 Transcript 格式与上下文序列化

从 `ralph-wiggum/hooks/stop-hook.sh` 中解析 transcript 的逻辑，可以还原 Claude Code 的对话存储格式：

**JSONL 格式（每行一个 JSON 对象）**：

```jsonl
{"role":"user","message":{"content":[{"type":"text","text":"Build a REST API for todos"}]}}
{"role":"assistant","message":{"content":[{"type":"text","text":"I'll implement the API step by step."},{"type":"tool_use","name":"Write","input":{"file_path":"server.js","content":"..."}}]}}
{"role":"user","message":{"content":[{"type":"tool_result","tool_use_id":"abc123","output":"File written successfully."}]}}
{"role":"assistant","message":{"content":[{"type":"text","text":"Now let me run the tests."},{"type":"tool_use","name":"Bash","input":{"command":"npm test"}}]}}
```

**消息结构推断**：

```
Message
├── role: "user" | "assistant" | "system"
├── message.content: ContentBlock[]
│   ├── TextBlock:    { "type": "text", "text": "..." }
│   ├── ToolUseBlock: { "type": "tool_use", "name": "Bash", "input": {...} }
│   └── ToolResultBlock: { "type": "tool_result", "tool_use_id": "...", "output": "..." }
└── [可能的元数据]: timestamp, model, tokenCount
```

**Transcript 在 Hook 系统中的使用**：
- `stop-hook.sh` 通过 `grep '"role":"assistant"' transcript_path` 提取所有 assistant 消息
- 取最后一条 assistant 消息的 text content 进行 `<promise>` 标签匹配
- `stop.py` 将整个 transcript 文件内容加载为字符串用于 `not_contains` 检查

**对 LyClaw 的启示**：

当前 LyClaw 使用 JSONL 文件存储（`session_*.jsonl`），与 Claude Code 一致。未来需要：
1. **双存储**：JSONL（完整记录，便于调试和重放） + SQLite（结构化查询，便于统计和分析）
2. **增量索引**：每次写入 JSONL 时同步更新 SQLite 索引（消息数、token数、工具调用统计）
3. **分层读取**：最近 N 条消息全量加载，历史消息摘要加载
4. **上下文压缩**：类似 ContextEngine 的 `compact` 功能——将早期的详细对话压缩为摘要

### 6.3 上下文压缩策略推断

虽然 ContextEngine 的实现不可见，但从以下线索可以推断其压缩策略：

**线索1: `ralph-wiggum` 的迭代保持上下文**
- 每次迭代不启动新进程（那会丢失上下文）
- 通过 Stop Hook 在同一会话中继续
- 这意味着上下文（包括之前的工具调用结果）在迭代间保持

**线索2: Agent 子任务的结果传递**
- 子 Agent 返回结果给主 Agent（而非重新读取整个对话）
- 主 Agent "读取 Agent 识别的关键文件"
- 这是一种**选择性上下文传递**——只传递结果摘要，不传递完整对话

**线索3: 工具调用的上下文窗口管理**
- `KillShell` 和 `BashOutput` 在工具白名单中出现
- 暗示长时间运行的 Bash 命令可以被 kill 并获取部分输出
- 这是一种**上下文窗口保护机制**——避免无限的工具输出撑爆上下文

**推断的压缩策略**：
```
┌─────────────────────────────────────────────┐
│ 完整上下文 (10万+ tokens)                    │
│  ┌───────────────────────────────────────┐  │
│  │ 早期对话 (压缩为摘要)  ← compact()    │  │
│  │ "用户要求构建REST API，经过3轮讨论..." │  │
│  ├───────────────────────────────────────┤  │
│  │ 中期对话 (选择性保留)                 │  │
│  │ 关键工具调用结果 (Read的文件内容等)    │  │
│  ├───────────────────────────────────────┤  │
│  │ 近期对话 (完整保留)                   │  │
│  │ 最近N条消息 + 工具调用 + 工具结果     │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

### 6.4 文件系统作为 Agent 间通信介质

Claude Code 的一个关键设计是：**文件系统是 Agent 间的主要通信介质**。

在 `feature-dev` 命令的编排中，这个模式非常明显：

```
Phase 2 (探索): 
  code-explorer Agent 返回 "关键文件列表"
  → 主 Agent 使用 Read 工具读取这些文件
  → 主 Agent 建立对代码库的深度理解
  → 这种理解存储在会话上下文中（而非单独的文件）

Phase 4 (架构设计):
  code-architect Agent 返回 "架构蓝图"
  → 主 Agent 读取蓝图内容
  → 等待用户确认后传递给 Phase 5

Phase 6 (审查):
  code-reviewer Agent 返回 "问题列表"
  → 主 Agent 汇总 → 用户决策
```

**这种设计的优点**：
- 解耦：Agent 之间不需要直接的 IPC
- 可审计：所有中间产物都是可以查看的文件
- 可恢复：会话中断后可以从文件系统状态恢复

**这种设计的缺点**：
- 文件 I/O 开销
- 并发写入冲突（需要文件锁或原子操作）
- 中间产物清理问题

**对 LyClaw 的映射**：
- 当前 LyClaw 子 Agent 通过返回值传递结果（Java 方法调用）
- 未来应支持"文件系统中介"模式——子 Agent 将大型结果写入临时文件，父 Agent 按需读取
- 参考 `ralph-wiggum` 的原子文件更新：`sed → temp → mv`（避免并发损坏）

### 6.5 CLAUDE.md 的层次化继承

从 code-review 命令的 Step 2 可以明确看到 CLAUDE.md 的**路径感知继承**：

```
Step 2: 查找所有相关 CLAUDE.md 文件
  规则：收集根目录 CLAUDE.md + 修改文件所在目录及所有父目录的 CLAUDE.md

审查时的路径匹配规则（Agent 1+2）：
  "When evaluating CLAUDE.md compliance for a file, you should only consider
   CLAUDE.md files that share a file path with the file or parents."
  
  即：文件 src/auth/login.ts 只受以下 CLAUDE.md 约束：
    - /CLAUDE.md           (根)
    - /src/CLAUDE.md       (父目录)
    - /src/auth/CLAUDE.md  (直接父目录)
  
  不受以下约束：
    - /src/api/CLAUDE.md   (不同分支)
    - /tests/CLAUDE.md     (不同分支)
```

**对LyClaw的启示**：LyClaw 的记忆系统可以设计为类似的层次化结构：

```java
public record MemoryScope(
    ScopeType type,     // GLOBAL, PROJECT, MODULE, AGENT, SESSION
    String path,        // 作用域路径（如 "src/auth/"）
    int priority        // 优先级（低优先级被高优先级覆盖）
) {}

public class HierarchicalMemory {
    // 获取特定文件/Agent的所有相关记忆
    List<Memory> resolveMemories(String filePath, String agentId) {
        // 收集: GLOBAL + PROJECT + 路径匹配的MODULE + AGENT + SESSION
        // 按优先级合并，子级覆盖父级
    }
}
```

### 6.6 Agent Skills 知识注入系统

`plugin-dev` 插件展示了完整的 **Skills 系统**——这是 LyClaw RAG 知识库的理想参考模型。

**Skills 目录结构**（以 agent-development 为例）：

```
skills/agent-development/
├── SKILL.md                          # 技能入口（frontmatter + 概要）
├── references/                       # 深度参考资料
│   ├── system-prompt-design.md       # System Prompt 设计模式（4种Agent模式）
│   ├── triggering-examples.md        # 触发示例编写指南
│   └── agent-creation-system-prompt.md # Agent创建系统提示词
├── examples/                         # 完整示例
│   ├── agent-creation-prompt.md      # 创建Agent的prompt示例
│   └── complete-agent-examples.md    # 完整Agent定义示例
└── scripts/                          # 辅助脚本
    └── validate-agent.sh             # Agent配置校验脚本
```

**SKILL.md 格式**：

```markdown
---
name: Agent Development
description: This skill should be used when the user asks to "create an agent",
  "generate an agent", "build a new agent", or describes agent functionality they need.
version: 1.0.0
---

# Agent Development Skill

[Skill的完整指导内容：如何设计Agent、编写System Prompt、选择工具、测试等]
```

**Skills 的使用方式**：
- Skill 不是 Agent，而是**注入到 Agent 上下文中的知识包**
- 当主Agent检测到用户需求匹配某个 Skill 的 `description` 时，自动加载该 Skill 的内容
- Skill 的 `references/` 和 `examples/` 按需加载，避免上下文过载

**对LyClaw的启示**：这正是 LyClaw RAG 知识库的理想设计：
- **Skill = 知识库条目**：结构化的知识包（SKILL.md + references + examples + scripts）
- **按需注入**：根据 Agent 角色和当前任务动态检索相关 Skills
- **分层加载**：SKILL.md（概要）→ references/（详细）→ examples/（示例），按需逐层展开
- **前端管理界面**：可视化的 Skill 编辑器，支持 Markdown 预览

RAG 检索流程设计：
```
用户任务 → 向量化 → 检索相关 Skills（语义相似度） → 按优先级合并 → 注入 Agent System Prompt
```

### 6.7 System Prompt 设计工程

从 `plugin-dev/skills/agent-development/references/system-prompt-design.md` 提炼的四种 Agent System Prompt 设计模式：

#### 模式1：Analysis Agents（分析型）

```
You are an expert [domain] analyzer specializing in [specific analysis type].

**Your Core Responsibilities:**
1. Thoroughly analyze [what] for [specific issues]
2. Identify [patterns/problems/opportunities]
3. Provide actionable recommendations

**Analysis Process:**
1. **Gather Context**: Read [what] using available tools
2. **Initial Scan**: Identify obvious [issues/patterns]
3. **Deep Analysis**: Examine [specific aspects]
4. **Synthesize Findings**: Group related issues
5. **Prioritize**: Rank by [severity/impact/urgency]
6. **Generate Report**: Format according to output template

**Output Format:**
## Summary | ## Critical Issues | ## Major Issues | ## Minor Issues | ## Recommendations
```

#### 模式2：Generation Agents（生成型）

```
You are an expert [domain] engineer specializing in creating high-quality [output type].

**Generation Process:**
1. **Understand Requirements**: Analyze what needs to be created
2. **Gather Context**: Read existing [code/docs/tests] for patterns
3. **Design Structure**: Plan [architecture/organization/flow]
4. **Generate Content**: Create [output] following [conventions]
5. **Validate**: Verify [correctness/completeness]
6. **Document**: Add comments/explanations as needed
```

#### 模式3：Validation Agents（验证型）

```
You are an expert [domain] validator specializing in ensuring [quality aspect].

**Validation Process:**
1. **Load Criteria**: Understand validation requirements
2. **Scan Target**: Read [what] needs validation
3. **Check Rules**: For each rule: [validation method]
4. **Collect Violations**: Document each failure with details
5. **Assess Severity**: Categorize issues
6. **Determine Result**: Pass only if [criteria met]

**Output Format:**
## Validation Result: [PASS/FAIL]
## Violations Found: [count]
  ### Critical / ### Warnings
```

#### 模式4：Orchestration Agents（编排型）

```
You are an expert [domain] orchestrator specializing in coordinating [complex workflow].

**Orchestration Process:**
1. **Plan**: Understand full workflow and dependencies
2. **Prepare**: Set up prerequisites
3. **Execute Phases**: Phase 1/2/3 with specific tools
4. **Monitor**: Track progress and handle failures
5. **Verify**: Confirm successful completion
6. **Report**: Provide comprehensive summary
```

**设计原则总结**：
- 使用第二人称（"You are..."、"You will..."）
- 具体而非模糊（"Check for SQL injection by examining all database queries for parameterization" 而非 "Look for security issues"）
- 可操作的步骤（"Read the file using Read tool" 而非 "Analyze the code"）
- 长度建议：最小500词、标准1000-2000词、全面2000-5000词、避免超过10000词

---

## 七、CI/CD 中的 Agent 驱动自动化

### 7.1 Claude Code Action 详解

这是本仓库最重要的发现之一——**Claude Code 可以作为一个 GitHub Action 运行**：

```yaml
- name: Run Claude Code
  id: claude
  uses: anthropics/claude-code-action@v1
  with:
    anthropic_api_key: ${{ secrets.ANTHROPIC_API_KEY }}
    prompt: "/triage-issue REPO: ${{ github.repository }} ISSUE_NUMBER: ${{ github.event.issue.number }}"
    claude_args: "--model claude-sonnet-4-5-20250929"
    github_token: ${{ secrets.GITHUB_TOKEN }}
```

**关键参数**：
- `anthropic_api_key`：必需，API认证
- `prompt`：发送给Claude的指令（可以是 Slash Command）
- `claude_args`：传递给 `claude` CLI的额外参数（模型、模式等）
- `github_token`：GitHub API认证（用于 gh CLI操作）
- `allowed_non_write_users`：允许非写权限用户触发（安全敏感参数）

**工作原理推断**：
1. Action Runner 中安装 Claude Code CLI
2. 通过 `prompt` 参数传入 Slash Command（如 `/triage-issue`）
3. Claude 在 Runner 中执行命令（使用 `gh` CLI 操作 GitHub API）
4. 输出通过 Action 日志返回

### 7.2 安全考量：非写用户权限

`non-write-users-check` 工作流体现了 Anthropic 对 Claude Code Action 安全的重视：

```yaml
# 当 PR 修改 .github/ 目录时
# 检查是否新增或修改了 allowed_non_write_users 字段
# 如果检测到 → 自动发布安全提醒评论

gh pr comment "$PR_NUMBER" --body '
⚠️ **allowed_non_write_users detected**
This PR adds or modifies allowed_non_write_users, which allows users
without write access to trigger Claude Code Action workflows.
This can introduce security risks.
[...contact AppSec team...]
'
```

**安全原则**：
- `allowed_non_write_users` 允许无仓库写权限的外部用户触发 Claude Code Action
- 这是一个高权限操作，需要额外的安全审查
- 自动检测 + 自动警告 = 安全左移

### 7.3 脚本驱动的自动化

并非所有自动化都需要 LLM。以下是使用 TypeScript/Bun 脚本的任务：

**`scripts/sweep.ts`** — 生命周期强制：
- 每天10am和10pm执行
- 扫描所有open issues
- 对贴了 `needs-repro`(7天) / `needs-info`(7天) / `stale`(14天) 标签且超时的issue自动关闭
- 关闭前发布说明评论

**`scripts/auto-close-duplicates.ts`** — 自动关闭重复Issue：
- 每天9am执行
- 扫描所有标记为 `duplicate` 的issues
- 对48小时无人响应的重复Issue自动关闭

**`scripts/lifecycle-comment.ts`** — 生命周期评论：
- Issue被贴标签时触发
- 对 `needs-repro`/`needs-info` 标签发布包含超时警告的评论
- 对 `stale` 标签发布"即将自动关闭"警告

**对LyClaw的启示**：并非所有自动化任务都需要 Agent/LLM。简单的规则引擎（类似Hookify的RuleEngine）可以处理大量日常运维任务。LLM Agent 应聚焦于需要语义理解的复杂决策。

---

## 八、设计模式总结

### 8.1 Agent 定义模式（声明式 + 自然语言触发）

Claude Code 的 Agent 定义是一种 **声明式 + 自然语言触发** 的混合模式：

```
Agent定义 = Frontmatter元数据(name/model/tools/color) 
          + 自然语言触发描述(description + <example>标签) 
          + 结构化System Prompt(角色+职责+流程+输出格式+边界情况)
```

**优点**：
- 低门槛：用 Markdown 就能定义 Agent
- 语义丰富：通过自然语言描述复杂的触发条件
- 自包含：每个 Agent 文件包含其完整的"身份"和"能力"

**缺点**：
- 触发不可靠：依赖 LLM 理解何时启动 Agent
- 缺乏类型安全：没有编译时验证
- 难以测试：Agent 行为难以自动化测试

**对LyClaw的平衡方案**：
| 层次 | Claude Code | LyClaw |
|------|------------|--------|
| Agent定义 | Markdown frontmatter | `@Agent` 注解 + 数据库字段 |
| Agent匹配 | LLM语义理解 description | 关键词 + 语义匹配 + LLM评分 混合 |
| 配置验证 | 无编译时检查 | plugin-validator 式的启动时校验 |
| 工具控制 | tools白名单字段 | ToolVisibilityPolicy 三级策略 |

### 8.2 多 Agent 编排的三种核心模式

#### 模式1：Command Sequential（阶段式顺序编排）

```
Command → Phase1 → [UserConfirm] → Phase2 → [ParallelAgents] → Phase3 → [UserConfirm] → ...
```

- **代表**：`/feature-dev` (7阶段), `/code-review` (9步骤)
- **适用**：需要人工决策点的复杂多阶段任务
- **LyClaw映射**：Pipeline + ReAct循环 + 用户交互Hook
- **关键特征**：明确的阶段门控（gate），每个阶段完成后检查条件才进入下一阶段

#### 模式2：Parallel Audit（并行审查 + 二级验证）

```
Task → [Agent1, Agent2, Agent3, Agent4] (并行) → [Validator1, Validator2, ...] (并行验证) → Filter → Output
```

- **代表**：`/code-review` 的 Step 4-6
- **适用**：需要多视角审查的质量保证任务
- **LyClaw映射**：SubagentSpawner 并行模式 + ResultAggregator + QualityGate
- **关键特征**：冗余 → 验证 → 过滤 三层质量保证

#### 模式3：Iterative Loop（自引用迭代编排）

```
Task → Execute → [StopHook] → block → Same Task → Execute → ... 
                         → allow → Exit
```

- **代表**：`ralph-wiggum`
- **适用**：需要反复迭代直到满足条件的自主任务
- **LyClaw映射**：Response Stage 的 ContinueCheckHook + 状态持久化
- **关键特征**：Hook 驱动循环，不需要外部 while-true wrapper

### 8.3 Hook 系统的 Fail-Open 设计

```
核心原则：
1. Hook 失败 ≠ 阻断操作（所有脚本 exit 0）
2. 输入/输出通过 stdin/stdout（JSON 协议，语言无关）
3. 每个 Hook 有独立 timeout（默认10秒）
4. 用户可自定义 Hook 规则（.local.md 文件）
5. Hook 可以：allow、warn(注入systemMessage)、deny(block工具)、block+continue(Stop事件)
```

**这是 Hook 设计的"黄金法则"**：Hook 是安全增强，但不是可用性障碍。如果一个 Hook 挂了（Python环境问题、脚本bug、超时），绝不因此阻断正常操作。

---

## 九、对 LyClaw 设计的具体建议

### 9.1 立即实施（Agent CRUD 阶段）

#### 1. Agent 定义标准化
Agent 表的字段应包括：
- `description`：AgentRouter语义匹配用（借鉴 Claude Code 的 description + examples 模式）
- `tools_allowlist`：工具白名单（声明式最小权限）
- `model_preference`：模型优选（haiku/sonnet/opus 对应轻量/标准/深度）
- `system_prompt_template`：模板化System Prompt（支持变量替换）
- `trigger_examples`：JSON格式存储触发场景示例（用于AgentRouter的语义匹配）
- `color`：UI展示颜色标识

#### 2. Hook 系统增强
当前 Pipeline 中的 AgentHook 接口扩展为完整的 Hook 链：
```java
public sealed interface HookResult {
    record Allow() implements HookResult {}
    record Warn(String message) implements HookResult {}
    record Deny(String message) implements HookResult {}
    record BlockAndContinue(String reason, String message) implements HookResult {}
}

public interface PipelineHook {
    HookResult onPreToolUse(String toolName, Map<String, Object> toolInput);
    HookResult onPostToolUse(String toolName, Map<String, Object> toolInput, Map<String, Object> toolOutput);
    HookResult onBeforeResponse(String lastOutput, String transcriptPath);
    HookResult onUserPrompt(String userPrompt);
}
```

#### 3. Agent 模板系统
参考 `agent-creator` 的模式，让用户可以通过对话自动生成 Agent：
```
用户："我需要一个检查SQL注入的Agent"
→ AgentRouter 匹配模板 → TemplateAgentFactory 生成配置 → 保存DB → DynamicAgentRegistry 注册
```

预设模板应包括四类：
- **分析型**：code_reviewer, bug_hunter, security_auditor, performance_analyzer
- **生成型**：test_writer, doc_writer, code_generator, api_designer
- **验证型**：config_validator, schema_checker, dependency_auditor
- **编排型**：task_orchestrator, pipeline_designer, workflow_coordinator

#### 4. 并行子Agent 执行
SubagentSpawner 应支持：
```java
// 并行启动
List<CompletableFuture<AgentResult>> futures = spawner.spawnParallel(
    new SpawnRequest("code-reviewer-1", "Review for style"),
    new SpawnRequest("code-reviewer-2", "Review for bugs"),
    new SpawnRequest("code-reviewer-3", "Review for security")
);

// 聚合结果
ResultAggregator aggregator = new ResultAggregator()
    .withDeduplication()      // 去重
    .withConfidenceFilter(80) // 置信度过滤
    .withSeveritySort();      // 按严重性排序
```

### 9.2 中期实施（编排雏形阶段）

#### 5. Command/Workflow 系统
引入类似 Slash Command 的多阶段工作流定义：
```yaml
name: feature-dev
description: Guided feature development with 7 phases
phases:
  - id: exploration
    agents: [code-explorer]
    parallel: true
    agent_count: 3
  - id: design
    agents: [code-architect]
    parallel: true
    wait_for_user: true
  - id: review
    agents: [code-reviewer, comment-analyzer, silent-failure-hunter]
    parallel: true
```

#### 6. 二级验证机制
```java
public class ValidatedResult {
    // 第一阶段：Agent审查
    List<Issue> rawIssues;
    
    // 第二阶段：独立验证
    List<Issue> validatedIssues;  // 被验证Agent确认的
    List<Issue> rejectedIssues;   // 被验证Agent拒绝的（误报）
    
    // 对每个rawIssue:
    //   启动独立验证Agent → 确认或拒绝 → 分类
}
```

#### 7. 事件驱动的 Agent 触发
```java
@EventListener
public void onGitHubWebhook(GitHubEvent event) {
    if (event.type() == WebhookType.ISSUE_OPENED) {
        agentTriggerService.trigger("issue-triage-agent", event.payload());
    }
}

@Scheduled(cron = "0 9 * * *")
public void scheduledCodeReview() {
    agentTriggerService.trigger("daily-code-review-agent", Map.of());
}
```

### 9.3 远期实施（记忆系统 + RAG 完成后）

#### 8. Agent Skills 知识注入
```java
public class SkillInjector {
    public String injectSkills(String systemPrompt, String agentRole, String task) {
        // 1. 向量化 task
        // 2. 从 RAG 知识库检索相关 Skills
        // 3. 按优先级合并 Skill 内容
        // 4. 注入到 System Prompt
        return enhancedPrompt;
    }
}
```

#### 9. 层次化记忆
四层记忆模型：
- **全局记忆**：用户偏好和规范（类似 CLAUDE.md 根级别）
- **项目记忆**：项目级别的约定和知识
- **Agent 记忆**：特定 Agent 的历史经验
- **会话记忆**：当前对话的上下文

#### 10. 自迭代循环
类似 Ralph Wiggum 的机制：
```java
@Component
public class ContinueCheckHook implements PipelineHook {
    @Override
    public HookResult onBeforeResponse(String lastOutput, String transcriptPath) {
        if (isCompletionPromiseDetected(lastOutput)) {
            return new HookResult.Allow();
        }
        if (isMaxIterationsReached()) {
            return new HookResult.Allow();
        }
        return new HookResult.BlockAndContinue(
            originalPrompt,
            "🔄 Iteration " + nextIteration() + " | Output <promise>DONE</promise> to complete"
        );
    }
}
```

### 9.4 架构调整建议

#### 保持的优势
1. **JDK 动态代理**：LyClaw 的核心差异化优势——类型安全、IDE友好、编译时检查
2. **5 Stage Pipeline**：天然对应 Claude Code 的4种Hook事件
3. **ToolProvider SPI**：可扩展为通用的 PluginProvider SPI
4. **DelegateToAgentToolProvider**：与 Claude Code 的 Agent 委派模式一致

#### 建议调整
1. **Agent 注册从注解驱动到数据库驱动**：`@Agent` 注解 → JDK代理（编译时） + 数据库 → 动态代理（运行时）
2. **Pipeline 增加显式 Hook 拦截点**：在5个Stage的边界增加 `HookChain`
3. **工具可见性从全局到按Agent过滤**：`ToolRegistry.getTools(agent)` 而非 `ToolRegistry.getAllTools()`
4. **Session 扩展为 Agent 间通信介质**：子Agent的输出应可被父Agent读取

### 9.5 Agent 间通信协议设计（参考 Claude Code 的 Task 工具）

Claude Code 的 `Task` 工具是实现 Agent 间委派的核心机制。虽然其源码不可见，但从 Command 的使用模式可以推断其协议：

```
主Agent调用 Task 工具:
{
  "tool": "Task",
  "input": {
    "agent": "code-reviewer",          // 目标Agent名称
    "prompt": "Review file X for bugs", // 委派的任务描述
    "context_files": ["file1.ts", "file2.ts"], // 上下文文件列表
    "model": "opus"                    // 可选：模型指定
  }
}

Task 工具返回:
{
  "result": "## Code Review Results\n...",  // Agent 的结构化输出
  "files_read": ["file1.ts", "file2.ts"],   // Agent 读取的文件
  "files_modified": [],                      // Agent 修改的文件
  "token_usage": { "input": 5000, "output": 1500 }
}
```

**对 LyClaw 的建议实现**：

```java
public record AgentTaskRequest(
    String agentName,           // 目标 Agent
    String prompt,              // 委派任务描述
    List<String> contextFiles,  // 上下文文件路径
    ModelPreference model,      // 模型偏好
    Map<String, Object> metadata // 扩展元数据
) {}

public record AgentTaskResult(
    String output,              // Agent 的结构化输出
    List<String> filesRead,     // Agent 读取的文件列表
    List<String> filesModified, // Agent 修改的文件列表
    TokenUsage tokenUsage,      // Token 消耗统计
    long durationMs,            // 执行耗时
    boolean success,            // 是否成功
    String errorMessage         // 错误信息（失败时）
) {}

public interface AgentTaskExecutor {
    AgentTaskResult execute(AgentTaskRequest request);
    CompletableFuture<AgentTaskResult> executeAsync(AgentTaskRequest request);
}
```

### 9.6 前端 Agent 编排界面的设计参考

从 Claude Code 的命令设计可以推断前端需要支持的编排交互模式：

**模式1：进度展示（Ralph Wiggum 风格）**
```
┌────────────────────────────────────────────┐
│ 🔄 Ralph Loop: Building REST API          │
│ ┌──────────────────────────────────────┐   │
│ │ Iteration 12/50  ████████░░ 24%      │   │
│ │ Last action: Fixed test assertion    │   │
│ │ Tests passing: 8/12 (67%)            │   │
│ │ Output <promise>ALL_TESTS_PASS</>    │   │
│ │ to complete                          │   │
│ └──────────────────────────────────────┘   │
│ [Pause] [Stop] [View Log]                  │
└────────────────────────────────────────────┘
```

**模式2：并行 Agent 审查展示**
```
┌────────────────────────────────────────────┐
│ 🔍 Code Review: PR #42                    │
│ ┌──────────┐ ┌──────────┐ ┌────────────┐  │
│ │ Reviewer1│ │ Reviewer2│ │ Security   │  │
│ │ Style ✓  │ │ Style ✓  │ │ Audit ⏳   │  │
│ │ Done     │ │ Done     │ │ Running... │  │
│ └──────────┘ └──────────┘ └────────────┘  │
│ ┌──────────────────────────────────────┐   │
│ │ Validation Agent: verifying issues...│   │
│ └──────────────────────────────────────┘   │
│ Issues found: 5 (3 validated, 2 rejected)  │
└────────────────────────────────────────────┘
```

**模式3：多阶段工作流进度**
```
┌────────────────────────────────────────────┐
│ 📋 Feature Development: user-auth         │
│ ✅ Phase 1: Discovery (done)              │
│ ✅ Phase 2: Exploration (3 agents)        │
│ ⏸️  Phase 3: Clarifying Questions         │
│    → "Should we use JWT or session?"      │
│    [JWT] [Session] [Custom...]            │
│ ⏳ Phase 4: Architecture (waiting)        │
│ ⏳ Phase 5: Implementation (waiting)      │
│ ⏳ Phase 6: Review (waiting)              │
│ ⏳ Phase 7: Summary (waiting)             │
└────────────────────────────────────────────┘
```

**对 LyClaw 前端的具体建议**：
- `ChatView` 中嵌入 `AgentProgressCard` 组件（显示迭代进度/并行Agent状态）
- `AppHeader` 中增加 `WorkflowIndicator`（显示当前命令/工作流阶段）
- 支持用户中断正在运行的 Agent 循环（通过 `KillShell` 或类似的终止机制）
- Agent 输出中的结构化数据（置信度评分、验证状态）应该用特殊 UI 渲染（而非纯文本）

---

## 十、安全架构分析

### 10.1 多层安全防护体系

从仓库中的安全相关配置和代码可以还原 Claude Code 的4层安全防护：

```
Layer 1: 设置级安全 (settings.json)
  ├─ disableBypassPermissionsMode: 禁止跳过权限
  ├─ strictKnownMarketplaces: 限制插件来源
  ├─ allowManagedPermissionRulesOnly: 企业权限规则
  ├─ allowManagedHooksOnly: 企业Hook管理
  └─ sandbox: Bash沙箱配置

Layer 2: 权限级安全 (permissions)
  ├─ deny: 工具黑名单 (WebSearch, WebFetch)
  ├─ ask: 工具需审批 (Bash)
  └─ allow: 工具白名单 (Read)

Layer 3: Hook级安全 (PreToolUse)
  ├─ security-guidance: 9种安全模式监控
  ├─ hookify: 可配置规则引擎
  └─ bash_command_validator: 命令验证示例

Layer 4: CI/CD级安全 (GitHub Actions)
  ├─ non-write-users-check: 检测allowed_non_write_users变更
  ├─ secrets.GITHUB_TOKEN: 最小权限token
  └─ concurrency控制: 防止并发安全问题
```

### 10.2 security-guidance 插件的9种安全监控模式

`security-guidance` 插件通过 PreToolUse Hook 监控以下安全模式：

| # | 安全模式 | 检测内容 | 风险等级 |
|---|---------|---------|---------|
| 1 | Command Injection | 用户输入拼接到shell命令（无引号/转义） | CRITICAL |
| 2 | XSS (Cross-Site Scripting) | innerHTML/dangerouslySetInnerHTML/未转义输出 | CRITICAL |
| 3 | Eval Injection | eval()/Function()/setTimeout(string) 含动态内容 | CRITICAL |
| 4 | Dangerous HTML | 动态HTML拼接、未净化用户输入 | HIGH |
| 5 | Pickle Deserialization | pickle.loads() 处理不可信数据 | CRITICAL |
| 6 | OS System Calls | os.system()/subprocess(shell=True) 处理动态输入 | HIGH |
| 7 | SQL Injection | 字符串拼接构建SQL查询 | CRITICAL |
| 8 | Hardcoded Secrets | API_KEY/SECRET/TOKEN/PASSWORD 硬编码 | HIGH |
| 9 | Path Traversal | ../ 路径遍历、文件包含漏洞 | HIGH |

**Hook 工作方式**：
```
Agent准备 Write/Edit 文件 → PreToolUse Hook 触发
  → security-guidance 检查文件内容和路径
  → 匹配安全模式 → deny + "⚠️ Security Issue Detected: ..."
  → 未匹配 → allow (exit 0)
```

### 10.3 bash_command_validator 示例 Hook

`examples/hooks/bash_command_validator` 展示了最简单的 Hook 实现模式：

```bash
#!/bin/bash
# 读取 Hook 输入
HOOK_INPUT=$(cat)

# 提取命令
COMMAND=$(echo "$HOOK_INPUT" | jq -r '.tool_input.command // empty')

# 危险命令模式
DANGEROUS_PATTERNS=(
  "rm -rf /"
  "chmod 777 /"
  "dd if="
  "mkfs."
  ":(){ :|:& };:"  # fork bomb
)

# 检查
for pattern in "${DANGEROUS_PATTERNS[@]}"; do
  if [[ "$COMMAND" == *"$pattern"* ]]; then
    jq -n --arg cmd "$COMMAND" '{
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny"
      },
      systemMessage: "**[SECURITY]** Blocked dangerous command: \($cmd)"
    }'
    exit 0
  fi
done

# 允许
exit 0
```

**安全设计原则总结**：
1. **白名单优于黑名单**：尽可能用 allowlist 而非 denylist
2. **最小权限**：Agent 只获得完成任务所需的最小工具集
3. **深度防御**：多层安全检查（settings → permissions → hooks → CI/CD）
4. **Fail-Safe**：安全检查失败时默认阻止（与 Hook 的 fail-open 不同——安全 Hook 可以配置为 fail-closed）
5. **审计日志**：所有安全决策记录在 transcript 中，可供事后审计

---

## 十一、架构对比矩阵

| 维度 | Claude Code | OpenClaw | LyClaw（目标） |
|------|------------|----------|---------------|
| **Agent 定义** | Markdown frontmatter | TypeScript class | `@Agent` 注解 + DB 配置 |
| **多Agent编排** | Command驱动 + 并行审查 | sessions_spawn + sessions_send | DelegateToAgentTool + Workflow |
| **子Agent触发** | LLM语义理解 description | 显式 spawnSubagent() | 关键词+语义+LLM评分混合 |
| **上下文传递** | CLAUDE.md 层次化 + Skills | assemble/compact/bootstrap | 层次化记忆 + RAG注入 |
| **Hook系统** | PreTool/PostTool/Stop/UserPromptSubmit | 无显式Hook | Pipeline Stage Hooks |
| **工具控制** | tools字段白名单 + 权限设置 | ToolAvailabilityExpression | ToolVisibilityPolicy |
| **记忆系统** | CLAUDE.md + .local.md + Transcript | ContextEngine插件 | RAG知识库 + 四层记忆 |
| **迭代循环** | Ralph Wiggum Stop Hook | 无内置 | Response Stage ContinueCheckHook |
| **CI/CD集成** | Claude Code Action (GitHub Actions) | 无 | Spring Boot Actuator + Webhook |
| **插件系统** | 约定式目录 + marketplace.json | npm包 | Spring Bean + PluginProvider SPI |
| **实现语言** | Node.js/TypeScript | Node.js/TypeScript | Java 21 + Spring Boot 3.x |
| **代理机制** | 无（直接调用） | 无（直接调用） | JDK动态代理 |
| **前端** | 终端TUI | 终端TUI | Vue 3 + TypeScript (Web UI) |
| **存储** | 文件系统（JSONL, .local.md） | 文件系统 | SQLite + JSONL 双存储 |
| **质量保证** | 冗余+验证+置信度过滤 | 无内置 | 计划 Phase 4 实现 |
| **SDK/可扩展性** | 插件系统 + MCP | npm包 + 插件系统 | Spring Bean + SPI + MCP |

---

## 十二、附录

### 附录A：完整插件清单

| # | 插件名 | 版本 | 作者 | Agent数 | Command数 | Skills |
|---|--------|------|------|---------|-----------|--------|
| 1 | feature-dev | 1.0.0 | Sid Bidasaria | 3 | 1 | 0 |
| 2 | pr-review-toolkit | 1.0.0 | Daisy | 6 | 1 | 0 |
| 3 | code-review | 1.0.0 | Boris Cherny | 0(内联) | 1 | 0 |
| 4 | commit-commands | 1.0.0 | Anthropic | 0 | 3 | 0 |
| 5 | hookify | 0.1.0 | Daisy Hollman | 1 | 4 | 1 |
| 6 | ralph-wiggum | 1.0.0 | Daisy Hollman | 0 | 3 | 0 |
| 7 | plugin-dev | 未指定 | Anthropic | 3 | 1 | 7 |
| 8 | frontend-design | 未指定 | Anthropic | 0 | 0 | 1 |
| 9 | security-guidance | 未指定 | Anthropic | 0 | 0 | 0(Hook only) |
| 10 | agent-sdk-dev | 未指定 | Anthropic | 2 | 1 | 0 |
| 11 | explanatory-output-style | 未指定 | Anthropic | 0 | 0 | 0(Hook only) |
| 12 | learning-output-style | 未指定 | Anthropic | 0 | 0 | 0(Hook only) |
| 13 | claude-opus-4-5-migration | 未指定 | Anthropic | 0 | 0 | 1 |

### 附录B：完整 Agent 清单

| Agent名 | 插件 | Model | Color | Tools | 类型 |
|---------|------|-------|-------|-------|------|
| code-explorer | feature-dev | sonnet | yellow | Glob,Grep,LS,Read,NotebookRead,WebFetch,TodoWrite,WebSearch,KillShell,BashOutput | 分析型 |
| code-architect | feature-dev | sonnet | green | Glob,Grep,LS,Read,NotebookRead,WebFetch,TodoWrite,WebSearch,KillShell,BashOutput | 生成型 |
| code-reviewer | feature-dev | sonnet | red | Glob,Grep,LS,Read,NotebookRead,WebFetch,TodoWrite,WebSearch,KillShell,BashOutput | 分析型 |
| code-reviewer | pr-review-toolkit | opus | green | 默认集 | 分析型 |
| code-simplifier | pr-review-toolkit | opus | 未指定 | 默认集 | 生成型 |
| comment-analyzer | pr-review-toolkit | inherit | green | 默认集 | 分析型 |
| pr-test-analyzer | pr-review-toolkit | inherit | cyan | 默认集 | 分析型 |
| silent-failure-hunter | pr-review-toolkit | inherit | yellow | 默认集 | 分析型 |
| type-design-analyzer | pr-review-toolkit | inherit | pink | 默认集 | 分析型 |
| conversation-analyzer | hookify | inherit | yellow | Read,Grep | 分析型 |
| agent-creator | plugin-dev | sonnet | magenta | Write,Read | 生成型 |
| plugin-validator | plugin-dev | 未指定 | 未指定 | 默认集 | 验证型 |
| skill-reviewer | plugin-dev | 未指定 | 未指定 | 默认集 | 验证型 |
| agent-sdk-verifier-py | agent-sdk-dev | 未指定 | 未指定 | 默认集 | 验证型 |
| agent-sdk-verifier-ts | agent-sdk-dev | 未指定 | 未指定 | 默认集 | 验证型 |

### 附录C：Hook 事件完整映射

| Hook事件 | hookify处理脚本 | 规则文件event类型 | 规则字段 | 输出能力 |
|----------|----------------|------------------|---------|---------|
| PreToolUse | pretooluse.py | bash / file | command, file_path, new_text, old_text, content | deny / warn / allow |
| PostToolUse | posttooluse.py | bash / file | 同PreToolUse + tool_output | warn / allow |
| Stop | stop.py | stop | reason, transcript (从文件读取) | block(含新prompt) / allow |
| UserPromptSubmit | userpromptsubmit.py | prompt | user_prompt | warn / allow |

### 附录D：参考信息来源

- Claude Code GitHub 仓库：`https://github.com/anthropics/claude-code`
- Claude Code 官方文档：`https://code.claude.com/docs/en/overview`
- Claude Code 插件文档：`https://docs.claude.com/en/docs/claude-code/plugins`
- Claude Code Settings 文档：`https://code.claude.com/docs/en/settings`
- Claude Code Hooks 文档：`https://docs.anthropic.com/en/docs/claude-code/hooks`
- Claude Code Action：`https://github.com/anthropics/claude-code-action`
- Ralph Wiggum 技术：`https://ghuntley.com/ralph/`
- Ralph Orchestrator：`https://github.com/mikeyobrien/ralph-orchestrator`
- Anthropic 商业条款：`https://www.anthropic.com/legal/commercial-terms`
- Claude Developers Discord：`https://anthropic.com/discord`

### 附录E：frontend-design 技能分析

`frontend-design` 插件包含一个全栈前端设计技能，展示了 Skills 系统的完整能力：

**技能结构**（`skills/frontend-design/SKILL.md`）：
- 设计理念：bold visual design（大胆的视觉设计）
- 技术栈：HTML/CSS/JS/React/Next.js/Vue/Nuxt/Svelte/Kit
- 设计方法论：不模板化，每次设计都独特
- 实现要求：生产级质量、完整功能、仔细架构

**技能注入模式**：
```
用户描述前端需求
  → 主Agent检测到 frontend-design skill 匹配
  → 加载 SKILL.md 作为上下文增强
  → Agent 获得前端设计领域的专业知识（设计理念、技术栈偏好、实现标准）
  → 生成高质量前端代码
```

**对 LyClaw 的启示**：
- Skills 不只是"参考文档"，而是改变 Agent 行为的"专业知识注入"
- 好的 Skill 包含：设计理念 + 技术约束 + 实现标准 + 反面示例
- LyClaw 的 RAG 知识库应支持 "Skill Override"——当加载某个 Skill 时，覆盖 Agent 的默认行为

### 附录F：output-style 插件分析

`explanatory-output-style` 和 `learning-output-style` 两个插件展示了 **输出风格控制** 的 Hook 模式：

**explanatory-output-style 的 Hook 配置**：
```json
{
  "hooks": {
    "Stop": [
      {
        "type": "command",
        "command": "python3 hooks/stop.py",
        "timeout": 10000
      }
    ]
  }
}
```

**工作原理推断**：
```
Agent 准备输出最终回复
  → Stop Hook 触发
  → Hook 检查输出内容是否符合风格要求
  → 不符合 → block + "请用更详细的解释风格重新输出"
  → 符合 → allow
```

**可定义的风格维度**：
- 详细程度：explanatory（详细解释）、concise（简洁）、tutorial（教程式）
- 受众水平：beginner（初学者）、intermediate（中级）、expert（专家）
- 输出格式：tutorial（教程）、reference（参考）、cookbook（食谱式）
- 语言偏好：中文、英文、双语

**对 LyClaw 的启示**：
- 输出风格可以作为 Agent 配置的一个维度（类似 model/color）
- 可以在 Pipeline 的 Response Stage 增加 `StyleCheckHook`
- 前端可提供"输出风格选择器"——用户切换风格后实时生效

### 附录G：关键数据流总结

**多Agent协作的完整数据流**：

```
用户输入: "Build a REST API for todos"
  │
  ├─ [UserPromptSubmit Hook] → 检查/增强提示词
  │
  ├─ [Command Router] → 匹配 /feature-dev 命令
  │   └─ 加载 feature-dev.md 作为编排指令
  │
  ├─ [Phase 1: Discovery] → 主Agent 理解需求
  │
  ├─ [Phase 2: Exploration] → 并行启动 3 个 code-explorer Agent
  │   ├─ Agent A → "找到相似功能并追踪实现" → 返回文件列表
  │   ├─ Agent B → "映射架构和抽象层次" → 返回架构图
  │   └─ Agent C → "分析现有实现" → 返回分析报告
  │   └─ 主Agent 读取所有返回的文件 → 建立代码库理解
  │
  ├─ [Phase 3: Questions] → 主Agent 提问 → 用户回答
  │
  ├─ [Phase 4: Design] → 并行启动 3 个 code-architect Agent
  │   ├─ Agent A → 最小改动方案
  │   ├─ Agent B → 干净架构方案
  │   └─ Agent C → 务实平衡方案
  │   └─ 主Agent 汇总 → 用户选择
  │
  ├─ [Phase 5: Implementation] → 主Agent 实现
  │   ├─ [PreToolUse Hook] → 安全检查 (每次工具调用)
  │   ├─ [PostToolUse Hook] → 结果审核 (每次工具调用)
  │   └─ 文件系统 ← Write/Edit/Bash
  │
  ├─ [Phase 6: Review] → 并行启动 3 个 code-reviewer Agent
  │   ├─ Agent A → 代码质量
  │   ├─ Agent B → BUG检测
  │   └─ Agent C → 规范合规
  │   └─ 每个发现 → 独立验证Agent → 置信度过滤 (≥80)
  │
  ├─ [Phase 7: Summary] → 主Agent 总结
  │
  └─ [Stop Hook] → 检查是否满足完成条件 → allow/block
```

---

> **总结**：本仓库虽非 Claude Code 核心源码，但是理解其 Agent 编排思想的最佳窗口。通过对14个插件、12个GitHub Actions工作流、15个Agent定义、4种Hook事件类型的系统分析，我们提取了声明式Agent定义、LLM驱动触发、Command编排、冗余+验证质量保证、Hook Fail-Open设计、层次化CLAUDE.md记忆等关键模式。这些模式为 LyClaw 的 Agent CRUD → 编排雏形 → 记忆系统重设计 → 完整多Agent协作网络 的演进路线提供了清晰的参考架构。
