package lyjew.com.lyclaw.mesh;

/**
 * 工具作用域 —— 定义 Agent 的工具可见性和继承策略。
 *
 * <p>在 Agent Mesh 中，工具不再只是全局共享的。每个 Agent 可以有自己的
 * 私有工具集，也可以继承父 Agent 的工具。作用域控制工具的定义和执行。</p>
 *
 * <ul>
 *   <li>{@link #GLOBAL} — 工具对所有 Agent 可见（注册到全局 ToolRegistry）</li>
 *   <li>{@link #PRIVATE} — 工具仅对当前 Agent 可见（不注册到全局注册表）</li>
 *   <li>{@link #INHERIT} — 继承父 Agent 的工具集 + 叠加自己的私有工具（仅对子 Agent 有效）</li>
 * </ul>
 *
 * <p>通过 {@link lyjew.com.lyclaw.mesh.AgentSpec#getConfig()} 配置：</p>
 * <pre>{@code
 * AgentSpec spec = AgentSpec.builder()
 *     .agentId("my-agent")
 *     .config("toolScope", "PRIVATE")
 *     .build();
 * }</pre>
 */
public enum ToolScope {
    /** 全局可见（默认，注册到全局 ToolRegistry） */
    GLOBAL,

    /** 仅当前 Agent 可见 */
    PRIVATE,

    /** 继承父 Agent + 叠加私有工具 */
    INHERIT
}
