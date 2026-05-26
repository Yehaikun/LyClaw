package lyjew.com.lyclaw.action.agent.decomposition;

import lyjew.com.lyclaw.action.agent.DefaultAgentRegistry;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class TaskDecomposer {

    private static final Logger log = LoggerFactory.getLogger(TaskDecomposer.class);

    private final ChatFacade chatFacade;
    private final DefaultAgentRegistry registry;

    public TaskDecomposer(ChatFacade chatFacade, DefaultAgentRegistry registry) {
        this.chatFacade = chatFacade;
        this.registry = registry;
    }

    /**
     * Decompose a complex task into a DAG of sub-tasks.
     * Uses LLM for complex tasks, rule-based for simple ones.
     */
    public TaskGraph decompose(String task, List<AgentHandle> availableAgents) {
        if (task == null || task.isBlank()) {
            return new TaskGraph(List.of(), List.of());
        }

        if (task.length() > 200) {
            return llmDecompose(task, availableAgents);
        }
        return ruleDecompose(task);
    }

    private TaskGraph ruleDecompose(String task) {
        List<TaskNode> nodes = new ArrayList<>();
        List<TaskEdge> edges = new ArrayList<>();
        String t = task.toLowerCase();

        if (t.contains("implement") || t.contains("实现") || t.contains("开发")) {
            String dbId = "db-" + uuid8();
            String apiId = "api-" + uuid8();
            String frontId = "front-" + uuid8();
            String testId = "test-" + uuid8();

            nodes.add(new TaskNode(dbId, "数据库设计与建表"));
            nodes.add(new TaskNode(apiId, "后端 API 开发"));
            nodes.add(new TaskNode(frontId, "前端页面开发"));
            nodes.add(new TaskNode(testId, "集成测试"));

            edges.add(new TaskEdge(dbId, apiId));
            edges.add(new TaskEdge(apiId, frontId));
            edges.add(new TaskEdge(frontId, testId));
        } else if (t.contains("analyze") || t.contains("分析")) {
            String dataId = "data-" + uuid8();
            String reportId = "report-" + uuid8();
            nodes.add(new TaskNode(dataId, "数据收集与整理"));
            nodes.add(new TaskNode(reportId, "分析报告生成"));
            edges.add(new TaskEdge(dataId, reportId));
        } else {
            String singleId = "task-" + uuid8();
            nodes.add(new TaskNode(singleId, task));
        }

        log.info("Rule decomposition: {} nodes, {} edges", nodes.size(), edges.size());
        return new TaskGraph(nodes, edges);
    }

    private TaskGraph llmDecompose(String task, List<AgentHandle> availableAgents) {
        try {
            String prompt = buildDecomposePrompt(task, availableAgents);
            ChatRequest request = ChatRequest.builder()
                    .messages(new java.util.ArrayList<>(List.of(Message.user(prompt))))
                    .stream(false)
                    .model("deepseek-chat")
                    .build();

            String response = chatFacade.chat(request).getContent();

            // 解析 LLM 响应，期望 JSON 格式的 TaskGraph
            return parseGraphFromLlmResponse(response, task);

        } catch (Exception e) {
            log.warn("LLM decomposition failed, falling back to rule-based: {}", e.getMessage());
            return ruleDecompose(task);
        }
    }

    private String buildDecomposePrompt(String task, List<AgentHandle> availableAgents) {
        StringBuilder sb = new StringBuilder();
        sb.append("将以下任务分解为子任务列表。每个子任务应可独立分配给一个 Agent 执行。\n\n");
        sb.append("任务: ").append(task).append("\n\n");

        sb.append("可用 Agent:\n");
        for (AgentHandle h : availableAgents) {
            sb.append("- ").append(h.getAgentId());
            sb.append(" (能力: ").append(String.join(", ", h.getCapabilities())).append(")\n");
        }

        sb.append("\n以 JSON 格式输出子任务列表:\n");
        sb.append("{\"subtasks\": [\n");
        sb.append("  {\"id\": \"唯一ID\", \"description\": \"子任务描述\", \"dependsOn\": [\"前置任务ID列表\"]}\n");
        sb.append("]}\n");
        sb.append("注意: dependsOn 为空列表表示无依赖的根任务。");
        return sb.toString();
    }

    private TaskGraph parseGraphFromLlmResponse(String response, String task) {
        List<TaskNode> nodes = new ArrayList<>();
        List<TaskEdge> edges = new ArrayList<>();

        try {
            String jsonStr = extractJson(response);
            if (jsonStr == null) {
                log.warn("No JSON found in LLM response, using fallback");
                return ruleDecompose(task);
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jsonStr);
            com.fasterxml.jackson.databind.JsonNode subtasks = root.get("subtasks");

            if (subtasks != null && subtasks.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode sub : subtasks) {
                    String id = sub.has("id") ? sub.get("id").asText() : "sub-" + uuid8();
                    String desc = sub.has("description") ? sub.get("description").asText() : "子任务";

                    TaskNode node = new TaskNode(id, desc);
                    nodes.add(node);

                    if (sub.has("dependsOn") && sub.get("dependsOn").isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode dep : sub.get("dependsOn")) {
                            edges.add(new TaskEdge(dep.asText(), id));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse LLM graph: {}", e.getMessage());
            return ruleDecompose(task);
        }

        if (nodes.isEmpty()) {
            return ruleDecompose(task);
        }
        log.info("LLM decomposition: {} nodes, {} edges", nodes.size(), edges.size());
        return new TaskGraph(nodes, edges);
    }

    private String extractJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private static String uuid8() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
