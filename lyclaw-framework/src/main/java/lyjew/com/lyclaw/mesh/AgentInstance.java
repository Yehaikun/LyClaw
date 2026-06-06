package lyjew.com.lyclaw.mesh;

import java.util.concurrent.CompletableFuture;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Agent 运行时实例 —— 消息驱动的执行单元。
 *
 * <p>系统中所有 Agent 的统一抽象。不管是 LLM Agent、工具 Agent、还是编排器 Agent，
 * 都实现此接口。通过 {@link #send(AgentMessage)} 接收消息并返回响应。</p>
 *
 * <p>三种内置实现：
 * <ul>
 *   <li>{@link lyjew.com.lyclaw.mesh.impl.LLMAgentInstance} —— 全量 LLM Agent，
 *       有自己的 system prompt + tools，内部走 ReAct 循环</li>
 *   <li>{@link lyjew.com.lyclaw.mesh.impl.ToolAgentInstance} —— 无状态工具 Agent，
 *       封装单个 Tool 的执行</li>
 *   <li>{@link lyjew.com.lyclaw.mesh.impl.ProxyAgentInstance} —— 包装旧 @Agent 接口
 *       的 JDK 代理（向后兼容）</li>
 * </ul>
 */
public interface AgentInstance {

    /** Agent 唯一标识 */
    String getAgentId();

    /** Agent 运行时类型 */
    AgentRef.AgentType getType();

    /** Agent 创建时的蓝图 */
    AgentSpec getSpec();

    /** Agent 运行时句柄（含状态、健康度） */
    AgentHandle getHandle();

    // ── 消息驱动核心 ──

    /**
     * 向此 Agent 发送消息并等待响应。
     *
     * <p>同步使用：{@code AgentMessage response = agent.send(request).join();}</p>
     * <p>异步使用：{@code agent.send(request).thenAccept(response -> ...);}</p>
     *
     * @param message 入站消息
     * @return 响应消息的 Future
     */
    CompletableFuture<AgentMessage> send(AgentMessage message);

    /**
     * 向此 Agent 发送消息并以流式方式接收响应。
     *
     * <p>用于流式生成场景：LLM 生成文本时逐 token 返回 STREAM 消息，
     * 最后返回一个 RESPONSE 消息标记结束。</p>
     *
     * @param message 入站消息
     * @return 流式响应消息流
     */
    Flux<AgentMessage> sendStream(AgentMessage message);

    // ── 生命周期 ──

    /** 启动 Agent（初始化资源） */
    void start();

    /** 停止 Agent（排空进行中的请求） */
    void stop();

    /** 销毁 Agent（释放所有资源） */
    void destroy();

    /** 当前生命周期状态 */
    AgentLifecycleState getState();

    /** 添加生命周期监听器 */
    void addLifecycleListener(AgentLifecycleListener listener);

    // ── 便捷方法 ──

    /** 发送纯文本请求并等待文本响应 */
    default String sendAndWait(String payload) {
        AgentMessage request = AgentMessage.builder()
                .type(MessageType.REQUEST)
                .payload(payload)
                .build();
        AgentMessage response = send(request).join();
        return response.getPayload();
    }

    /** 获取 Agent 的调用历史 */
    AgentCallHistory getCallHistory();
}
