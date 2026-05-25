package lyjew.com.lyclaw.reflect.impl.evaluator;

import static lyjew.com.lyclaw.react.SseEventTypes.MESSAGE;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.Issue;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.Severity;
import lyjew.com.lyclaw.reflect.primitive.Evaluator;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 驱动的多维度评估器，使用 ReAct 循环在评分前调用工具验证 Actor 输出。
 *
 * <p>与直接调用 ChatFacade 的简单评估不同，本评估器通过 ReActEngine 赋予 LLM
 * 工具调用能力：在评分前可搜索事实、执行代码、查询数据库来验证输出正确性，
 * 从而产生比纯启发式或纯文本评估更可靠的评分。
 *
 * <h3>评估维度</h3>
 * relevance（相关性）、correctness（正确性）、completeness（完整性）、clarity（清晰度）。
 *
 * <h3>降级策略</h3>
 * ReAct 循环失败或 JSON 解析异常时，降级为启发式评分，确保拓扑不被评估器阻塞。
 */
@Primitive(type = PrimitiveType.EVALUATOR, name = "llmJudge")
public class LLMJudgeEvaluator implements Evaluator {

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
    private static final Pattern JSON_BRACE = Pattern.compile("\\{[\\s\\S]*\"score\"[\\s\\S]*\\}");

    private static final String SYSTEM_PROMPT =
            "你是一个专业的输出评估器，具备工具调用能力。\n\n"
            + "工作流程：\n"
            + "1. 阅读待评估的 AI 输出，识别其中可验证的事实声明\n"
            + "2. 如有必要，使用工具（搜索、代码执行等）验证关键事实\n"
            + "3. 综合验证结果，给出多维度评分\n\n"
            + "评估维度（0.0=最差，1.0=最佳）：\n"
            + "- relevance：是否准确回应用户请求？\n"
            + "- correctness：信息是否准确、有事实依据？\n"
            + "- completeness：是否完整回答了问题？\n"
            + "- clarity：结构是否清晰、表达是否流畅？\n\n"
            + "最终请只返回 JSON，不要加 markdown 或其他文字。";

    private final ChatFacade chatFacade;
    private final ObjectMapper objectMapper;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;

    public LLMJudgeEvaluator(ChatFacade chatFacade, ObjectMapper objectMapper,
                             ReActEngine reActEngine, ToolRegistry toolRegistry) {
        this.chatFacade = chatFacade;
        this.objectMapper = objectMapper;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Evaluation evaluate(ReflectionContext ctx) {
        String output = ctx.getCurrentOutput();
        if (output == null || output.isBlank()) {
            return emptyEvaluation();
        }

        try {
            return reActEvaluate(ctx, output);
        } catch (Exception e) {
            return heuristicFallback(output, e.getMessage());
        }
    }

    @Override
    public Evaluation evaluateStream(ReflectionContext ctx, Consumer<String> chunkSink) {
        String output = ctx.getCurrentOutput();
        if (output == null || output.isBlank()) {
            return emptyEvaluation();
        }
        try {
            String prompt = buildEvalPrompt(ctx.getUserMessage(), output, ctx.getTaskSummary());
            List<ToolDefinition> tools = toolRegistry.getAllDefinitions();

            ChatRequest request = ChatRequest.builder()
                    .systemPrompt(SYSTEM_PROMPT)
                    .messages(new ArrayList<>(List.of(Message.user(prompt))))
                    .tools(tools)
                    .stream(true)
                    .build();

            ToolExecutor toolExecutor = createToolExecutor(request);
            StringBuilder full = new StringBuilder();

            reActEngine.executeStream(chatFacade, request, toolExecutor)
                    .doOnNext(sse -> {
                        if (MESSAGE.equals(sse.event()) && sse.data() != null) {
                            full.append(sse.data());
                            chunkSink.accept(sse.data());
                        }
                    })
                    .blockLast(Duration.ofMinutes(2));

            String finalOutput = full.toString();
            return parseEvaluation(finalOutput);
        } catch (Exception e) {
            return heuristicFallback(output, e.getMessage());
        }
    }

    /** 通过 ReAct 循环评估：LLM 可调用工具验证事实，最终返回评分 JSON */
    private Evaluation reActEvaluate(ReflectionContext ctx, String output) {
        String prompt = buildEvalPrompt(ctx.getUserMessage(), output, ctx.getTaskSummary());
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions();

        ChatRequest request = ChatRequest.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .messages(new ArrayList<>(List.of(Message.user(prompt))))
                .tools(tools)
                .build();

        ToolExecutor toolExecutor = createToolExecutor(request);
        String finalOutput = reActEngine.execute(chatFacade, request, toolExecutor);
        return parseEvaluation(finalOutput);
    }

    /** 封装 ToolRegistry 为 ToolExecutor，记录工具调用结果 */
    private ToolExecutor createToolExecutor(ChatRequest request) {
        return (toolName, toolCallId, argumentsJson) -> {
            ToolExecutionResult result = toolRegistry.executeByName(toolName, toolCallId, argumentsJson, request);
            return result.isSuccess() ? result.getResult()
                    : "[TOOL_ERROR] " + result.getError();
        };
    }

    /** 构建评估 prompt — 包含用户请求、待评估输出和评分维度 */
    private String buildEvalPrompt(String userMessage, String output, String taskSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("请对以下 AI 生成的输出进行评分。\n\n");

        if (userMessage != null && !userMessage.isBlank()) {
            sb.append("用户请求：").append(userMessage).append("\n\n");
        }
        if (taskSummary != null && !taskSummary.isBlank()) {
            sb.append("任务上下文：").append(taskSummary).append("\n\n");
        }

        sb.append("待评估输出：\n```\n").append(output).append("\n```\n\n");
        sb.append("评分维度（0.0=最差，1.0=最佳）：\n");
        sb.append("- relevance：是否准确回应用户请求？\n");
        sb.append("- correctness：信息是否准确、有事实依据？\n");
        sb.append("- completeness：是否完整回答了问题？\n");
        sb.append("- clarity：结构是否清晰、表达是否流畅？\n\n");
        sb.append("你可以使用工具验证输出中的事实声明。验证完成后，请精确返回以下 JSON（不要 markdown，不要额外文字）：\n");
        sb.append("{\"score\":0.85,\"dimensions\":{\"relevance\":0.9,\"correctness\":0.8,\"completeness\":0.85,\"clarity\":0.7},\"reasoning\":\"...\",\"isSuccess\":true,\"needsRetry\":false,\"issues\":[]}");

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Evaluation parseEvaluation(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RuntimeException("ReAct 评估返回空响应");
        }

        String json = extractJson(raw);
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});

            Evaluation eval = new Evaluation();
            eval.setScore(toDouble(map.get("score"), 0.5));
            eval.setReasoning((String) map.getOrDefault("reasoning", ""));
            eval.setSuccess(Boolean.TRUE.equals(map.get("isSuccess")));
            eval.setNeedsRetry(Boolean.TRUE.equals(map.get("needsRetry")));
            eval.setRawOutput(raw);

            Object dims = map.get("dimensions");
            if (dims instanceof Map) {
                Map<String, Double> dimMap = new java.util.LinkedHashMap<>();
                ((Map<String, Object>) dims).forEach((k, v) -> dimMap.put(k, toDouble(v, 0.0)));
                eval.setDimensions(dimMap);
            }

            Object issuesObj = map.get("issues");
            if (issuesObj instanceof List) {
                List<Issue> issues = new ArrayList<>();
                for (Object item : (List<?>) issuesObj) {
                    if (item instanceof Map) {
                        Map<String, Object> im = (Map<String, Object>) item;
                        Severity sev = Severity.MINOR;
                        try { sev = Severity.valueOf((String) im.get("severity")); } catch (Exception ignored) {}
                        issues.add(new Issue(sev,
                                (String) im.getOrDefault("category", "general"),
                                (String) im.getOrDefault("description", "")));
                    }
                }
                eval.setIssues(issues);
            }

            return eval;
        } catch (Exception e) {
            throw new RuntimeException("解析评估 JSON 失败: " + e.getMessage());
        }
    }

    /** 从 ReAct 最终输出中提取 JSON：优先匹配 ```json 代码块，其次匹配花括号包围的 JSON */
    private String extractJson(String raw) {
        Matcher m = JSON_BLOCK.matcher(raw);
        if (m.find()) return m.group(1).trim();
        m = JSON_BRACE.matcher(raw);
        if (m.find()) return m.group().trim();
        return raw.trim();
    }

    private double toDouble(Object v, double def) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private Evaluation emptyEvaluation() {
        Evaluation eval = new Evaluation();
        eval.setScore(0.0);
        eval.setSuccess(false);
        eval.setNeedsRetry(true);
        eval.setReasoning("输出为空");
        eval.getIssues().add(new Issue(Severity.CRITICAL, "output", "Actor 输出为空或纯空白"));
        return eval;
    }

    /** ReAct 调用失败时的降级评估：基于文本特征做启发式评分 */
    private Evaluation heuristicFallback(String output, String errorMsg) {
        Evaluation eval = new Evaluation();
        eval.setScore(computeHeuristicScore(output));
        eval.setSuccess(eval.getScore() >= 0.7);
        eval.setNeedsRetry(eval.getScore() < 0.7);
        eval.setReasoning("启发式降级评分（ReAct 评估失败: " + errorMsg + "）");
        eval.setRawOutput(output);

        if (eval.getScore() < 0.7) {
            eval.getIssues().add(new Issue(Severity.MAJOR, "evaluation",
                    "LLM 评估不可用，启发式评分=" + eval.getScore()));
        }
        return eval;
    }

    private double computeHeuristicScore(String output) {
        if (output == null || output.isBlank()) return 0.0;
        double score = 0.5;
        int len = output.length();
        String lower = output.toLowerCase();

        if (len < 50) score -= 0.3;
        else if (len < 100) score -= 0.1;
        else if (len > 8000) score -= 0.1;

        if (lower.contains("error") || lower.contains("exception") || lower.contains("failed"))
            score -= 0.3;

        for (String marker : new String[]{"research shows", "it is well known", "without a doubt",
                "experts agree", "definitely", "absolutely", "guaranteed"}) {
            if (lower.contains(marker)) { score -= 0.15; break; }
        }

        if (output.contains("```") || output.contains("- ") || output.contains("1. "))
            score += 0.1;

        return Math.max(0.0, Math.min(1.0, score));
    }
}
