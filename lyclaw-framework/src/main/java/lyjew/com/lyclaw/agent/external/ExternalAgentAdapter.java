package lyjew.com.lyclaw.agent.external;

import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.dto.AgentResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 外部代理适配器接口，定义与外部第三方代理交互的标准协议。
 *
 * ExternalAgentAdapter 是 LyClaw 系统对接外部代理的桥梁。它提供了一套
 * 完整的远程代理交互协议：首先通过 discover 发现外部代理的能力信息
 * （获取 AgentCard）；然后通过 sendTask 向外部代理异步提交任务；
 * queryTaskStatus 用于轮询远程任务的执行状态；cancelTask 支持取消
 * 已在远程执行的任务。所有方法都返回 CompletableFuture，以适配网络
 * 通信的异步特性，并通过 Duration 参数控制超时。
 */
public interface ExternalAgentAdapter {

    /**
     * 从指定端点发现外部代理，获取其名片信息。
     *
     * @param endpointUrl 外部代理的发现端点 URL（如 "https://agent.example.com/.well-known/agent.json"）
     * @return 异步返回外部代理的名片信息
     */
    CompletableFuture<AgentCard> discover(String endpointUrl);

    /**
     * 向外部代理提交一个异步任务。
     *
     * @param agentUrl 外部代理的任务提交端点 URL
     * @param task     待执行的任务
     * @param timeout  任务超时时间
     * @return 异步返回任务执行结果
     */
    CompletableFuture<AgentResult> sendTask(String agentUrl, AgentTask task, Duration timeout);

    /**
     * 查询远程任务的当前执行状态。
     *
     * @param agentUrl 外部代理的状态查询端点 URL
     * @param taskId   要查询的任务标识
     * @return 异步返回任务的当前状态
     */
    CompletableFuture<TaskStatus> queryTaskStatus(String agentUrl, String taskId);

    /**
     * 取消在远程代理上执行的任务。
     *
     * @param agentUrl 外部代理的取消端点 URL
     * @param taskId   要取消的任务标识
     * @return 异步返回取消是否成功
     */
    CompletableFuture<Boolean> cancelTask(String agentUrl, String taskId);
}
