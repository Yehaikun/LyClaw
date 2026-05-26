package lyjew.com.lyclaw.action.agent.router;

import lyjew.com.lyclaw.action.agent.DefaultAgentRegistry;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentRouter;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.agent.RoutingContext;
import lyjew.com.lyclaw.agent.RoutingDecision;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Order(60)
@ConditionalOnProperty(name = "lyclaw.agent.llm-routing.enabled", havingValue = "true", matchIfMissing = false)
public class LLMRouter implements AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(LLMRouter.class);

    private final DefaultAgentRegistry registry;
    private final ChatFacade chatFacade;

    public LLMRouter(DefaultAgentRegistry registry, ChatFacade chatFacade) {
        this.registry = registry;
        this.chatFacade = chatFacade;
    }

    @Override
    public RoutingDecision route(AgentTask task, RoutingContext context) {
        List<AgentHandle> candidates = registry.getAllAgents().stream()
                .filter(h -> h.getState() == lyjew.com.lyclaw.agent.AgentState.IDLE
                        || h.getState() == lyjew.com.lyclaw.agent.AgentState.RUNNING)
                .filter(h -> !h.getAgentId().equals(context.getParentAgentId()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return RoutingDecision.fallback("没有可用的 Agent 供 LLM 路由");
        }

        // 排除自身
        String prompt = buildRoutingPrompt(task, context, candidates);

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(new java.util.ArrayList<>(List.of(Message.user(prompt))))
                    .stream(false)
                    .model("deepseek-chat")
                    .build();

            String response = chatFacade.chat(request).getContent();
            return parseLlmDecision(response, candidates);

        } catch (Exception e) {
            log.warn("LLM routing failed, falling back: {}", e.getMessage());
            return RoutingDecision.fallback("LLM 路由失败: " + e.getMessage());
        }
    }

    @Override
    public String routerName() {
        return "llm";
    }

    private String buildRoutingPrompt(AgentTask task, RoutingContext ctx, List<AgentHandle> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("你需要从以下 Agent 列表中，选择最合适的一个来处理用户的任务。\n\n");
        sb.append("## 任务描述\n").append(task.getPayload()).append("\n\n");

        if (ctx.getUserMessage() != null && !ctx.getUserMessage().isEmpty()) {
            sb.append("## 用户原始消息\n").append(ctx.getUserMessage()).append("\n\n");
        }

        sb.append("## 可用 Agent 列表\n");
        for (AgentHandle h : candidates) {
            sb.append("- **").append(h.getAgentId()).append("**");
            if (h.getName() != null && !h.getName().equals(h.getAgentId())) {
                sb.append(" (").append(h.getName()).append(")");
            }
            sb.append("\n");
            if (h.getDescription() != null && !h.getDescription().isEmpty()) {
                sb.append("  描述：").append(h.getDescription()).append("\n");
            }
            if (h.getCapabilities() != null && !h.getCapabilities().isEmpty()) {
                sb.append("  能力：").append(String.join(", ", h.getCapabilities())).append("\n");
            }
        }
        sb.append("\n请从列表中选择最合适的 Agent ID，并简要说明原因。\n");
        sb.append("格式：{\"agentId\": \"...\", \"reason\": \"...\"}\n");
        sb.append("如果没有任何 Agent 适合，返回：{\"agentId\": null, \"reason\": \"...\"}");

        return sb.toString();
    }

    private RoutingDecision parseLlmDecision(String response, List<AgentHandle> candidates) {
        try {
            String jsonStr = response.trim();
            int start = jsonStr.indexOf('{');
            int end = jsonStr.lastIndexOf('}');
            if (start >= 0 && end > start) {
                jsonStr = jsonStr.substring(start, end + 1);
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(jsonStr);

            String agentId = node.has("agentId") && !node.get("agentId").isNull()
                    ? node.get("agentId").asText() : null;
            String reason = node.has("reason") ? node.get("reason").asText() : "LLM 决策";

            if (agentId != null && !agentId.isEmpty()) {
                boolean valid = candidates.stream().anyMatch(h -> h.getAgentId().equals(agentId));
                if (valid) {
                    return RoutingDecision.high(agentId, 0.7, "LLM 路由: " + reason, routerName());
                }
                return RoutingDecision.fallback("LLM 选择了无效的 agentId: " + agentId);
            }

            return RoutingDecision.fallback("LLM 认为无合适 Agent: " + reason);

        } catch (Exception e) {
            log.warn("Failed to parse LLM routing response: {}", e.getMessage());
            return RoutingDecision.fallback("LLM 响应解析失败");
        }
    }
}
