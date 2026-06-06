package lyjew.com.lyclaw.mesh;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import reactor.core.publisher.Flux;

/**
 * Agent Mesh 核心接口 —— 多 Agent 系统的注册中心 + 消息路由器 + 事件总线。
 *
 * <p>Agent Mesh 是整个多 Agent 调度架构的核心入口，职责包括：
 * <ul>
 *   <li><b>Agent 注册</b> —— 通过 {@link #register(AgentSpec)} 动态创建和注册 Agent</li>
 *   <li><b>消息路由</b> —— 通过 {@link #send(AgentMessage)} 将消息路由到目标 Agent</li>
 *   <li><b>能力查找</b> —— 通过 {@link #findByCapability(String)} 按能力发现 Agent</li>
 *   <li><b>事件总线</b> —— 通过 {@link #addListener(AgentMeshListener)} 订阅全局事件</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 通过 AgentSpec 动态注册一个 LLM Agent
 * AgentRef ref = mesh.register(AgentSpec.builder()
 *     .agentId("code-reviewer")
 *     .capability("code-review")
 *     .model("deepseek-v4")
 *     .systemPrompt("你是一个代码审查员...")
 *     .build());
 *
 * // 向该 Agent 发送消息
 * AgentMessage response = mesh.send(AgentMessage.builder()
 *     .to("code-reviewer")
 *     .payload("审查这个 PR")
 *     .build()).join();
 * }</pre>
 */
public interface AgentMesh {

    // ── Agent 注册 ──

    /**
     * 注册一个新的 Agent。
     * <p>根据 AgentSpec 创建 AgentInstance，注册到注册表，发布 AGENT_REGISTERED 事件。</p>
     *
     * @param spec Agent 创建蓝图
     * @return Agent 轻量级引用
     */
    AgentRef register(AgentSpec spec);

    /**
     * 注销一个 Agent。
     * <p>停止 Agent、释放资源、从注册表移除、发布 AGENT_UNREGISTERED 事件。</p>
     */
    void unregister(String agentId);

    // ── Agent 查找 ──

    /** 按 ID 查找 Agent 引用 */
    Optional<AgentRef> lookup(String agentId);

    /** 按能力查找 Agent 引用列表 */
    List<AgentRef> findByCapability(String capability);

    /** 按 ID 查找 Agent 运行时实例 */
    Optional<AgentInstance> getInstance(String agentId);

    /** 获取所有已注册的 Agent 引用 */
    List<AgentRef> getAllAgents();

    // ── 消息传递 ──

    /**
     * 发送消息并等待响应。
     * <p>同步：response = mesh.send(msg).join()</p>
     * <p>异步：mesh.send(msg).thenAccept(response -> ...)</p>
     *
     * @param message 消息
     * @return 响应消息的 Future
     */
    CompletableFuture<AgentMessage> send(AgentMessage message);

    /**
     * 发送消息并以流式方式接收响应。
     *
     * @param message 消息
     * @return 流式响应消息流
     */
    Flux<AgentMessage> sendStream(AgentMessage message);

    /**
     * 广播事件消息（不期望回复）。
     * <p>发送给所有注册的 Agent。</p>
     */
    void publish(AgentMessage event);

    // ── 延迟结果队列 ──

    /**
     * 查询延迟结果。
     * <p>当父 Agent 在子 Agent 返回前已经结束时，结果存入延迟队列。
     * 父 Agent 下次启动时可以从队列中获取。</p>
     */
    Optional<DelayedResult> pollDelayedResult(String agentId, String correlationId);

    /**
     * 存入延迟结果。
     */
    void storeDelayedResult(String agentId, String correlationId, AgentMessage result);

    // ── 生命周期管理 ──

    /** 启动指定 Agent */
    void startAgent(String agentId);

    /** 停止指定 Agent */
    void stopAgent(String agentId);

    /** 更新 Agent 生命周期状态 */
    void updateState(String agentId, AgentLifecycleState newState);

    // ── 事件监听 ──

    /** 添加 Mesh 全局事件监听器 */
    void addListener(AgentMeshListener listener);

    /** 移除 Mesh 全局事件监听器 */
    void removeListener(AgentMeshListener listener);

    /** 添加 Agent 实例（用于外部创建的实例，如 ProxyAgentInstance） */
    AgentRef addInstance(AgentInstance instance);
}
