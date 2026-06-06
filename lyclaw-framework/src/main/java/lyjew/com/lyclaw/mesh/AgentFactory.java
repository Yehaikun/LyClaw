package lyjew.com.lyclaw.mesh;

/**
 * Agent 实例工厂 —— 工厂方法模式。
 *
 * <p>根据 {@link AgentSpec} 创建对应的 {@link AgentInstance} 实现。
 * 根据 spec 的 type 字段决定具体实现：</p>
 * <ul>
 *   <li>{@link AgentRef.AgentType#LLM} → {@link lyjew.com.lyclaw.mesh.impl.LLMAgentInstance}</li>
 *   <li>{@link AgentRef.AgentType#TOOL} → {@link lyjew.com.lyclaw.mesh.impl.ToolAgentInstance}</li>
 *   <li>{@link AgentRef.AgentType#ORCHESTRATOR} → 编排器实现</li>
 *   <li>{@link AgentRef.AgentType#PROXY} → 旧 @Agent 接口包装</li>
 * </ul>
 */
public interface AgentFactory {

    /** 根据 Spec 创建 Agent 实例 */
    AgentInstance create(AgentSpec spec);
}
