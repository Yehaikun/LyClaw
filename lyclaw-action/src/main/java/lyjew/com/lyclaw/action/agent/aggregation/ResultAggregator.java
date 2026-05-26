package lyjew.com.lyclaw.action.agent.aggregation;

import lyjew.com.lyclaw.agent.CollaborationPattern;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.subagent.SubagentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ResultAggregator {

    private static final Logger log = LoggerFactory.getLogger(ResultAggregator.class);

    private final ChatFacade chatFacade;

    public ResultAggregator(ChatFacade chatFacade) {
        this.chatFacade = chatFacade;
    }

    public AggregatedResult aggregate(CollaborationPattern pattern, String task,
                                       List<SubagentResult> results, AgentContext ctx) {
        if (results == null || results.isEmpty()) {
            return AggregatedResult.failure("没有子 Agent 结果", pattern, List.of());
        }

        long startTime = System.currentTimeMillis();

        try {
            return switch (pattern) {
                case HIERARCHICAL -> aggregateHierarchical(task, results);
                case VOTE -> aggregateVote(task, results, ctx);
                case DEBATE -> aggregateDebate(task, results, ctx);
                case PIPELINE -> aggregatePipeline(task, results);
                default -> aggregateSimple(task, results);
            };
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Aggregation completed: pattern={}, results={}, elapsed={}ms",
                    pattern, results.size(), elapsed);
        }
    }

    private AggregatedResult aggregateHierarchical(String task, List<SubagentResult> results) {
        StringBuilder summary = new StringBuilder();
        StringBuilder detail = new StringBuilder();

        for (int i = 0; i < results.size(); i++) {
            SubagentResult r = results.get(i);
            String agentLabel = r.getAgentId() != null ? "@" + r.getAgentId() : "Agent[" + i + "]";

            if (r.isSuccess()) {
                summary.append("✓ ").append(agentLabel).append(" 完成\n");
                detail.append("### ").append(agentLabel).append(" 的输出\n");
                detail.append(r.getOutput() != null ? r.getOutput() : "(空)").append("\n\n");
            } else {
                summary.append("✗ ").append(agentLabel).append(" 失败: ")
                        .append(r.getError() != null ? r.getError() : "未知错误").append("\n");
            }
        }

        long totalMs = results.stream().mapToLong(SubagentResult::getDurationMs).sum();
        return AggregatedResult.success(summary.toString(), detail.toString(),
                CollaborationPattern.HIERARCHICAL, results, totalMs);
    }

    private AggregatedResult aggregateVote(String task, List<SubagentResult> results, AgentContext ctx) {
        List<SubagentResult> successful = results.stream()
                .filter(SubagentResult::isSuccess).collect(Collectors.toList());

        if (successful.isEmpty()) {
            return AggregatedResult.failure("所有 Agent 均执行失败", CollaborationPattern.VOTE, results);
        }

        if (successful.size() == 1) {
            SubagentResult r = successful.get(0);
            String summary = "唯一成功的结果来自 @" + r.getAgentId();
            return AggregatedResult.consensus(summary, results, 0.5, r.getDurationMs());
        }

        // LLM-as-Judge: 让 LLM 选择最佳结果
        try {
            String prompt = buildVotePrompt(task, successful);
            ChatRequest request = ChatRequest.builder()
                    .messages(new java.util.ArrayList<>(List.of(Message.user(prompt))))
                    .stream(false)
                    .model("deepseek-chat")
                    .build();
            String judgeResponse = chatFacade.chat(request).getContent();

            // 解析评判结果
            String bestAgentId = extractAgentId(judgeResponse);
            if (bestAgentId != null) {
                String summary = "LLM 评判选择 @" + bestAgentId;
                return AggregatedResult.consensus(summary, results, 0.7, 0);
            }

            // 默认选第一个
            SubagentResult best = successful.get(0);
            return AggregatedResult.consensus("默认选择 @" + best.getAgentId(), results, 0.5, 0);

        } catch (Exception e) {
            log.warn("Vote aggregation via LLM failed: {}", e.getMessage());
            SubagentResult best = successful.get(0);
            return AggregatedResult.consensus("默认选择 @" + best.getAgentId(), results, 0.5, 0);
        }
    }

    private AggregatedResult aggregateDebate(String task, List<SubagentResult> results, AgentContext ctx) {
        // 辩论聚合: 简单实现 — 检查所有成功结果是否一致
        List<SubagentResult> successful = results.stream()
                .filter(SubagentResult::isSuccess).collect(Collectors.toList());

        if (successful.isEmpty()) {
            return AggregatedResult.failure("辩论无共识", CollaborationPattern.DEBATE, results);
        }

        int allSameOpinion = 0;
        for (int i = 0; i < successful.size(); i++) {
            for (int j = i + 1; j < successful.size(); j++) {
                if (outputsSimilar(successful.get(i).getOutput(), successful.get(j).getOutput())) {
                    allSameOpinion++;
                }
            }
        }

        double agreementRatio = (double) allSameOpinion / Math.max(1, successful.size() * (successful.size() - 1) / 2);

        if (agreementRatio > 0.5) {
            return AggregatedResult.consensus("辩论达成共识 (一致性 " + String.format("%.0f", agreementRatio * 100) + "%)",
                    results, agreementRatio, 0);
        }

        // 无共识，汇总所有观点
        StringBuilder summary = new StringBuilder("辩论未达成共识。各方观点如下:\n\n");
        for (SubagentResult r : successful) {
            summary.append("- @").append(r.getAgentId()).append(": ");
            String output = r.getOutput();
            summary.append(output != null ? output.substring(0, Math.min(200, output.length())) : "(空)");
            summary.append("\n");
        }

        return AggregatedResult.success(summary.toString(), summary.toString(),
                CollaborationPattern.DEBATE, results, 0);
    }

    private AggregatedResult aggregatePipeline(String task, List<SubagentResult> results) {
        StringBuilder sb = new StringBuilder();
        for (SubagentResult r : results) {
            if (r.isSuccess() && r.getOutput() != null) {
                sb.append(r.getOutput()).append("\n\n");
            }
        }
        long totalMs = results.stream().mapToLong(SubagentResult::getDurationMs).sum();
        return AggregatedResult.success("流水线执行完成", sb.toString(),
                CollaborationPattern.PIPELINE, results, totalMs);
    }

    private AggregatedResult aggregateSimple(String task, List<SubagentResult> results) {
        StringBuilder sb = new StringBuilder();
        for (SubagentResult r : results) {
            if (r.isSuccess() && r.getOutput() != null) {
                sb.append(r.getOutput()).append("\n\n");
            }
        }
        return AggregatedResult.success("聚合完成", sb.toString(),
                CollaborationPattern.HANDOFF, results, 0);
    }

    private String buildVotePrompt(String task, List<SubagentResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是对同一任务的多 Agent 执行结果。请选择最佳结果。\n\n");
        sb.append("## 原始任务\n").append(task).append("\n\n");
        sb.append("## 各 Agent 结果\n\n");

        for (int i = 0; i < results.size(); i++) {
            SubagentResult r = results.get(i);
            sb.append("### Agent ").append(i + 1).append(": @").append(r.getAgentId()).append("\n");
            sb.append(r.getOutput() != null ? r.getOutput() : "(空)").append("\n\n");
        }

        sb.append("请输出最佳结果的 Agent 编号和原因。\n");
        sb.append("格式: {\"agentId\": \"...\", \"reason\": \"...\"}");
        return sb.toString();
    }

    private String extractAgentId(String text) {
        try {
            String json = text.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
            return node.has("agentId") && !node.get("agentId").isNull()
                    ? node.get("agentId").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean outputsSimilar(String a, String b) {
        if (a == null || b == null) return false;
        int len = Math.min(a.length(), b.length());
        if (len < 10) return a.equals(b);
        // Simple overlap check on first 200 chars
        String aPrefix = a.substring(0, Math.min(200, a.length())).toLowerCase();
        String bPrefix = b.substring(0, Math.min(200, b.length())).toLowerCase();
        return aPrefix.contains(bPrefix) || bPrefix.contains(aPrefix);
    }
}
