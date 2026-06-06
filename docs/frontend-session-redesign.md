# 前端重构 + 多 Agent 会话持久化设计

## 一、前端重构

### 现状问题

```
导航栏 10 个页面，只有 3 个真正对接后端：
  ✅ Chat / Sessions / Mesh  ← 有用的
  ❌ AgentView / Tools / Plan / Memory / Dashboard  ← mock 数据，无后端
  ⚠️ Models / Settings  ← 部分有用
```

### 新导航结构

```
┌─ 💬 Chat        ← 核心对话 + Agent 执行进度实时展示
├─ 📋 Sessions    ← 会话管理
├─ 🌐 Mesh        ← Agent 计算网格（注册/编排/监控）
├─ ⚙️ Settings    ← 模型/工具配置
└─ 🆕 New         ← 预留入口
```

**6 个页面下架**：`AgentView`(被 Mesh 替代)、`ToolsView`(归属 Agent)、`PlanView`(无后端)、`MemoryView`(无后端)、`DashboardView`(无后端)

### Chat 重构重点

Chat 页面当前 1080 行，需要：
1. 集成 Agent 执行进度面板（任务下发后实时显示）
2. 会话中显示子 Agent 的调用树
3. 每条消息可展开查看对应的 Agent 执行详情
4. 移除 mock 数据，全部对接后端

### MeshView 重构重点

MeshView 当前 623 行，需要：
1. Agent 实时状态面板（SSE 已就绪）
2. 编排执行时的实时进度展示
3. 每个 Agent 的可展开执行时间线
4. Agent 注册表单与生命周期管理

---

## 二、多 Agent 会话持久化

### 核心问题

```
当前一条会话 = 一个 Agent 的对话
但在 Agent Mesh 中：
  Agent A 收到任务 → 委托给子 Agent B → B 有自己的推理过程
  → B 的结果返回给 A → A 综合后回答用户

问题：B 的整个推理过程/工具调用/中间结果存在哪里？
```

### Session Tree 模型

```
用户会话 (sessionId: "sess-abc")
├── user: "帮我审查这个 PR"
├── assistant: "开始分析..."
│   ├── 调用了 github-tool (工具结果)
│   └── 委托 reviewer (子会话)
│       │
│       └── 子会话 (sessionId: "sess-abc/subagent/reviewer/xxx")
│           ├── system: "你是代码审查员..."
│           ├── user: "审查这个 diff: ..."
│           ├── assistant: "发现 3 个问题"
│           │   ├── 调用了 linter-tool (工具结果)
│           │   └── 思考过程...
│           └── tool: linter 结果
│
├── assistant: "审查完毕，发现..."
└── (继续)
```

### 存储模型

```
sessions 表（已存在，需扩展）：
  session_id, parent_session_id, agent_id, name, model, status, ...

messages 表（已存在，需增加 parent_msg_index）：
  session_id, msg_index, parent_session_id, parent_msg_index,
  role, content, tool_call_id, ...

session_tree 视图（通过 parent_session_id 关联）：
  用于查询任意会话的完整调用树
```

### 接口设计

```java
// SessionService 新增
public interface SessionService {
    // 创建子会话（在 Agent Mesh 中委托时调用）
    Session createChildSession(String parentSessionId, String agentId, String task);

    // 获取会话树（从根到所有子会话的所有消息）
    SessionTree getSessionTree(String sessionId);

    // 获取子会话列表
    List<Session> getChildSessions(String parentSessionId);

    // 将子会话结果关联到父会话的某条消息
    void linkChildResult(String parentSessionId, int parentMsgIndex,
                         String childSessionId, int childMsgIndex);
}

// SessionTree 模型
public class SessionTree {
    Session root;                    // 根会话
    List<Message> rootMessages;      // 根消息
    List<SessionBranch> branches;    // 子会话分支
}

public class SessionBranch {
    Session childSession;            // 子会话元数据
    List<Message> messages;          // 子会话消息
    int linkedFromMsgIndex;          // 关联到父会话的哪条消息
    List<SessionBranch> subBranches; // 递归子分支
}
```

### 前端展示

```
ChatView 中的会话树渲染：

用户: 帮我审查这个 PR
  ↓
AI 正在工作...
  ├── ◌ code-reviewer  审查代码中  60%
  │   ├── 📄 获取 diff        ✅
  │   ├── 🔍 运行 ESLint     ✅
  │   └── 📝 生成审查报告    ⏳
  └── ◌ 综合结果中...

AI: 审查完毕，发现 3 个问题：
    1. ...
```

### 实现计划

| Step | 内容 |
|------|------|
| 1 | Session 模型增加 `parentSessionId` 字段 |
| 2 | SessionService 增加 `createChildSession` / `getSessionTree` |
| 3 | LLMAgentInstance 在 spawn 子 Agent 时创建子会话 |
| 4 | 前端 SessionTree 组件渲染嵌套会话 |
| 5 | ChatView 集成 Agent 进度 + 会话树 |
