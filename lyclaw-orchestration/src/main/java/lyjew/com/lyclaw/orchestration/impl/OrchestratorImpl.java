package lyjew.com.lyclaw.orchestration.impl;

import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.feign.ActionFeignClient;
import lyjew.com.lyclaw.feign.MemoryFeignClient;
import lyjew.com.lyclaw.feign.PlanFeignClient;
import lyjew.com.lyclaw.feign.ReflectFeignClient;
import lyjew.com.lyclaw.memory.PerceptionData;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.orchestration.AgentEvent;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import lyjew.com.lyclaw.orchestration.Orchestrator;
import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.task.PlanRequest;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;

@Service
public class OrchestratorImpl implements Orchestrator {

    @Autowired
    private PlanFeignClient planFeignClient;

    @Autowired
    private ActionFeignClient actionFeignClient;

    @Autowired
    private ReflectFeignClient reflectFeignClient;

    @Autowired
    private MemoryFeignClient memoryFeignClient;

    @Override
    public Flux<String> execute(ChatContext context) {
        return Flux.create(sink -> {
            try {
                ChatRequest request = context.getRequest();
                String sessionId = request.getSessionId();
                String userMessage = request.getLastUserMessage();

                // Phase 1: Plan
                sink.next("event: plan_start\ndata: Planning task decomposition\n\n");
                PlanRequest planReq = PlanRequest.builder()
                        .sessionId(sessionId)
                        .userIntent(userMessage)
                        .strategy("default")
                        .build();
                TaskPlan plan = planFeignClient.plan(planReq);
                sink.next("event: plan_complete\ndata: Planned " + plan.getNodes().size() + " task(s)\n\n");

                // Phase 2: Action — execute each task node
                int nodeIndex = 0;
                for (TaskNode node : plan.getNodes()) {
                    nodeIndex++;
                    sink.next("event: action_start\ndata: Executing task [" + nodeIndex + "/"
                            + plan.getNodes().size() + "] " + node.getDescription() + "\n\n");
                    ToolExecuteRequest toolReq = ToolExecuteRequest.builder()
                            .toolName(node.getNodeId())
                            .sessionId(sessionId)
                            .build();
                    ToolResult result = actionFeignClient.executeTool(toolReq);
                    String output = result.isSuccess() ? result.getOutput() : result.getErrorMessage();
                    sink.next("event: action_result\ndata: " + output + "\n\n");
                }

                // Phase 3: Reflect
                sink.next("event: reflect_start\ndata: Reflecting on execution results\n\n");
                ReflectRequest reflectReq = ReflectRequest.builder()
                        .sessionId(sessionId)
                        .output(userMessage)
                        .build();
                ReflectionReport report = reflectFeignClient.reflect(reflectReq);
                sink.next("event: reflect_complete\ndata: Reflection score="
                        + report.getOverallScore() + "\n\n");

                // Phase 4: Memory — store execution trace
                sink.next("event: memory_start\ndata: Persisting to memory\n\n");
                PerceptionData perception = PerceptionData.builder()
                        .role("assistant")
                        .content("Orchestration pipeline completed for session: " + sessionId)
                        .timestamp(System.currentTimeMillis())
                        .metadata(Collections.emptyMap())
                        .build();
                memoryFeignClient.ingest(perception);
                sink.next("event: memory_complete\ndata: Memory persisted\n\n");

                // Completion
                sink.next("event: complete\ndata: Orchestration finished successfully\n\n");
                sink.complete();
            } catch (Exception e) {
                sink.next("event: error\ndata: " + e.getMessage() + "\n\n");
                sink.complete();
            }
        });
    }

    @Override
    public Flux<AgentEvent> executeAgentTask(OrchestrationContext context) {
        return Flux.just(
                AgentEvent.builder()
                        .type(AgentEvent.EventType.TASK_STARTED)
                        .agentId("orchestrator")
                        .data("Agent task execution started")
                        .timestamp(System.currentTimeMillis())
                        .build(),
                AgentEvent.builder()
                        .type(AgentEvent.EventType.TASK_COMPLETED)
                        .agentId("orchestrator")
                        .data("Agent task execution completed")
                        .timestamp(System.currentTimeMillis())
                        .build()
        );
    }

    @Override
    public boolean cancel(String collaborationId) {
        return false;
    }

    @Override
    public double getProgress(String collaborationId) {
        return 0.0;
    }
}
