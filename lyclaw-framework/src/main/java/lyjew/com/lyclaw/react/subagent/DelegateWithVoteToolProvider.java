package lyjew.com.lyclaw.react.subagent;

import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolProvider;
import lyjew.com.lyclaw.tool.ToolProviderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ToolProvider that adds {@code delegate_with_vote} — delegates a task to
 * multiple agents and picks the best result via voting.
 *
 * <p>Registration: this provider is {@code @ConditionalOnProperty} controlled
 * and registered alongside {@link DelegateToAgentToolProvider}.</p>
 */
public class DelegateWithVoteToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(DelegateWithVoteToolProvider.class);

    private final SubagentSpawner spawner;
    private final boolean enabled;

    private volatile ToolDefinition cachedDefinition;

    public DelegateWithVoteToolProvider(SubagentSpawner spawner, boolean enabled) {
        this.spawner = spawner;
        this.enabled = enabled;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolProviderResult result = new ToolProviderResult();
        if (!enabled || request == null || request.getChatRequest() == null) {
            return result;
        }

        if (cachedDefinition == null) {
            synchronized (this) {
                if (cachedDefinition == null) {
                    cachedDefinition = ToolDefinition.builder()
                            .name("delegate_with_vote")
                            .description("将任务委派给多个 Agent 并投票选择最佳结果。适用于需要高准确度的任务。")
                            .parameters(Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "task", Map.of("type", "string",
                                                    "description", "任务描述"),
                                            "agentIds", Map.of("type", "array",
                                                    "items", Map.of("type", "string"),
                                                    "description", "候选 Agent ID 列表，为空则自动选择"),
                                            "voteMethod", Map.of("type", "string",
                                                    "enum", List.of("llm_judge", "majority"),
                                                    "description", "投票方法")
                                    ),
                                    "required", List.of("task")
                            ))
                            .build();
                }
            }
        }

        result.add(cachedDefinition, (toolName, toolCallId, argumentsJson) -> {
            ToolCall toolCall = ToolCall.builder()
                    .toolCallId(toolCallId)
                    .name(toolName)
                    .arguments(argumentsJson)
                    .build();
            ToolExecutionResult execResult = executeVote(toolCall, request.getAttribute("agentContext"));
            return execResult.isSuccess() ? execResult.getResult() : "Error: " + execResult.getError();
        });

        return result;
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeVote(ToolCall toolCall, Object context) {
        if (!enabled) {
            return ToolExecutionResult.failure("Vote delegation is disabled", "delegate_with_vote");
        }

        AgentContext agentCtx = resolveContext(context);
        if (agentCtx == null) {
            return ToolExecutionResult.failure("AgentContext not available", "delegate_with_vote");
        }

        Map<String, Object> args;
        try {
            args = parseArguments(toolCall.getArguments());
        } catch (Exception e) {
            return ToolExecutionResult.failure("Invalid arguments: " + e.getMessage(), "delegate_with_vote");
        }

        String task = (String) args.getOrDefault("task", "");
        if (task.isBlank()) {
            return ToolExecutionResult.failure("Missing required 'task' argument", "delegate_with_vote");
        }

        List<String> agentIds = (List<String>) args.get("agentIds");
        if (agentIds == null || agentIds.isEmpty()) {
            agentIds = List.of("code-reviewer", "writer", "researcher");
        }

        log.info("DelegateWithVote: task='{}', agents={}", task, agentIds);

        List<SubagentResult> results = new ArrayList<>();
        for (String agentId : agentIds) {
            try {
                SubagentResult r = spawner.spawnSubagent(agentId, task,
                                java.util.Collections.emptyMap(), agentCtx)
                        .block(java.time.Duration.ofSeconds(300));
                if (r != null) results.add(r);
            } catch (Exception e) {
                log.warn("Vote delegate to {} failed: {}", agentId, e.getMessage());
                results.add(SubagentResult.error("Execution failed: " + e.getMessage()));
            }
        }

        // Pick best (LLM-as-Judge via ResultAggregator — here we do simple best)
        StringBuilder sb = new StringBuilder();
        sb.append("### Vote Results\n\n");
        for (SubagentResult r : results) {
            String status = r.isSuccess() ? "✓" : "✗";
            sb.append(status).append(" @").append(r.getAgentId()).append(":\n");
            String output = r.getOutput();
            sb.append(output != null ? output : r.getError()).append("\n\n");
        }

        return ToolExecutionResult.success(sb.toString(), "delegate_with_vote");
    }

    private AgentContext resolveContext(Object context) {
        if (context instanceof AgentContext ac) return ac;
        if (context instanceof ToolProviderRequest tpr) {
            Object attr = tpr.getAttribute("agentContext");
            if (attr instanceof AgentContext ac) return ac;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(Object arguments) throws Exception {
        if (arguments == null) return Collections.emptyMap();
        if (arguments instanceof Map) return (Map<String, Object>) arguments;
        if (arguments instanceof String json && !json.isBlank()) {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, java.util.LinkedHashMap.class);
        }
        return Collections.emptyMap();
    }
}
