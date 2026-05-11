package lyjew.com.lyclaw.protocol.a2a;

import lyjew.com.lyclaw.dto.AgentResult;

import java.util.concurrent.CompletableFuture;

/**
 * A2A 协议网关接口，定义代理间通信的核心操作。
 *
 * <p>该接口封装了 A2A（Agent-to-Agent）协议的标准交互流程：发现代理、
 * 发送任务、获取制品、取消任务以及注册本地代理。所有远程调用均为异步操作，
 * 返回 {@link CompletableFuture}，以避免阻塞主线程。</p>
 *
 * <p>A2aGateway 是代理间通信的统一入口，具体实现可以使用 HTTP、gRPC 等
 * 传输协议，只需遵循 A2A 协议的消息格式即可。</p>
 */
public interface A2aGateway {

    /**
     * 获取远程代理的 AgentCard（代理名片），其中包含代理的能力、端点等信息。
     *
     * @param agentUrl 远程代理的 URL 地址
     * @return 包含 AgentCard 的 CompletableFuture，失败时异常传播
     */
    CompletableFuture<A2aAgentCard> getAgentCard(String agentUrl);

    /**
     * 向远程代理发送任务请求。
     *
     * @param agentUrl 远程代理的 URL 地址
     * @param task     任务规格，包含任务 ID、描述、参数、超时等信息
     * @return 包含 AgentResult 的 CompletableFuture，表示任务的最终执行结果
     */
    CompletableFuture<AgentResult> sendTask(String agentUrl, A2aTaskSpec task);

    /**
     * 从远程代理获取指定任务的制品。
     *
     * @param agentUrl   远程代理的 URL 地址
     * @param taskId     任务 ID
     * @param artifactId 制品 ID
     * @return 包含制品的 CompletableFuture
     */
    CompletableFuture<A2aArtifact> getArtifact(String agentUrl, String taskId, String artifactId);

    /**
     * 取消远程代理上正在执行的指定任务。
     *
     * @param agentUrl 远程代理的 URL 地址
     * @param taskId   要取消的任务 ID
     * @return true 表示取消成功，false 表示取消失败或任务不存在
     */
    boolean cancelTask(String agentUrl, String taskId);

    /**
     * 向本地网关注册一个本地代理，使其可被其他代理发现和调用。
     *
     * @param card 本地代理的 AgentCard
     */
    void registerLocalAgent(A2aAgentCard card);
}
