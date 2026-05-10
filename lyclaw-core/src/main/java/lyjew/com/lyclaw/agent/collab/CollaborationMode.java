package lyjew.com.lyclaw.agent.collab;

import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 协作模式 —— 多 Agent 协作的策略接口。
 *
 * <p>支持 SupervisorWorker / NetworkCollaboration / PipelineCollaboration / MarketCollaboration。
 * 新增模式只需 @Component + implements CollaborationMode, Spring 自动发现注册。</p>
 *
 * @since 2.0
 */
public interface CollaborationMode {

    String getModeId();

    TopologyType getPreferredTopology();

    AssignmentPlan assign(List<AgentHandle> availableAgents, OrchestrationContext ctx);

    CompletableFuture<AgentResult> execute(CollaborationContext ctx);

    boolean cancel(String collaborationId);

    double getProgress(String collaborationId);

    boolean supportsDynamicScaling();
}
