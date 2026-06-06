package lyjew.com.lyclaw.mesh;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Agent 轻量级引用 —— 系统中所有 Agent 的身份标识（ActorRef 模式）。
 *
 * <p>AgentRef 是框架使用者操作 Agent 的唯一方式，它不绑定到任何具体的运行时实现。
 * Agent 可以在本地 JVM、远程进程、甚至是另一个数据中心，通过 AgentRef 发送消息
 * 都是位置透明的。</p>
 *
 * <p>核心设计：
 * <ul>
 *   <li><b>不可变</b> —— AgentRef 一旦创建就不会改变，可安全地跨线程共享</li>
 *   <li><b>可序列化</b> —— 可以传递给子 Agent、存储在数据库中、跨网络传输</li>
 *   <li><b>轻量级</b> —— 只包含标识和能力声明，不含运行时引用</li>
 * </ul>
 *
 * <p>AgentType 枚举定义了 Agent 的运行时类型：
 * <ul>
 *   <li>{@code LLM} —— 全量 LLM Agent，有自己的 system prompt + tools + LLM</li>
 *   <li>{@code TOOL} —— 工具 Agent，无状态单步执行</li>
 *   <li>{@code ORCHESTRATOR} —— 编排器 Agent，负责路由和协调</li>
 *   <li>{@code PROXY} —— 包装旧 @Agent 接口的代理（向后兼容）</li>
 * </ul>
 */
public final class AgentRef {

    private final String agentId;
    private final AgentType type;
    private final Set<String> capabilities;
    private final long createdAt;

    public AgentRef(String agentId, AgentType type, Set<String> capabilities) {
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        this.type = type != null ? type : AgentType.LLM;
        this.capabilities = capabilities != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(capabilities))
                : Set.of();
        this.createdAt = System.currentTimeMillis();
    }

    public String getAgentId() { return agentId; }
    public AgentType getType() { return type; }
    public Set<String> getCapabilities() { return capabilities; }
    public long getCreatedAt() { return createdAt; }

    /** 是否具备指定能力 */
    public boolean hasCapability(String capability) {
        return capability != null && capabilities.contains(capability);
    }

    /** 是否具备所有指定能力 */
    public boolean hasAllCapabilities(Set<String> required) {
        if (required == null || required.isEmpty()) return true;
        return capabilities.containsAll(required);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentRef ref)) return false;
        return agentId.equals(ref.agentId);
    }

    @Override
    public int hashCode() { return agentId.hashCode(); }

    @Override
    public String toString() {
        return "AgentRef{" + agentId + " [" + type + "]" + "}";
    }

    public static AgentRef of(String agentId) {
        return new AgentRef(agentId, AgentType.LLM, Set.of());
    }

    /** Agent 运行时类型枚举 */
    public enum AgentType {
        /** 全量 LLM Agent：有自己的 system prompt + tools + LLM，走 ReAct 循环 */
        LLM,
        /** 工具 Agent：无状态，封装单个 Tool 的执行 */
        TOOL,
        /** 编排器 Agent：负责路由和协调其他 Agent */
        ORCHESTRATOR,
        /** 旧 @Agent 接口的代理（向后兼容） */
        PROXY
    }
}
