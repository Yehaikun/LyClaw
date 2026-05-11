package lyjew.com.lyclaw.protocol.a2a;

/**
 * 代理能力枚举，描述 AI 代理支持的各种功能领域。
 *
 * <p>该枚举用于 {@link A2aAgentCard} 中的 capabilities 列表，
 * 让调用方在发起任务前了解目标代理的能力范围，从而决定是否向其委派任务。</p>
 *
 * <ul>
 *   <li>{@code TEXT_GEN} - 文本生成，包括对话、翻译、摘要等</li>
 *   <li>{@code TOOL_USE} - 工具调用，能够使用外部工具/函数</li>
 *   <li>{@code CODE_EXEC} - 代码执行，能够在沙箱中运行代码</li>
 *   <li>{@code RAG} - 检索增强生成，能够从知识库中检索信息</li>
 *   <li>{@code COMPUTER_USE} - 计算机操作，能够操控 GUI 界面</li>
 *   <li>{@code PLANNING} - 规划能力，能够分解复杂任务并制定执行计划</li>
 *   <li>{@code REFLECTION} - 反思能力，能够对自身输出进行自我检查和改进</li>
 *   <li>{@code MEMORY_MANAGEMENT} - 记忆管理，能够持久化和检索上下文记忆</li>
 * </ul>
 */
public enum AgentCapability {
    /** 文本生成 */
    TEXT_GEN,
    /** 工具调用 */
    TOOL_USE,
    /** 代码执行 */
    CODE_EXEC,
    /** 检索增强生成 */
    RAG,
    /** 计算机操作 */
    COMPUTER_USE,
    /** 规划 */
    PLANNING,
    /** 反思 */
    REFLECTION,
    /** 记忆管理 */
    MEMORY_MANAGEMENT
}
