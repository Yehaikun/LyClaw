package lyjew.com.lyclaw.reflect.impl.router;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RouteDecision;
import lyjew.com.lyclaw.reflect.primitive.Router;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import lyjew.com.lyclaw.common.StringUtils;

/**
 * LLM 驱动的路由决策器 — 将当前上下文、评估结果和迭代状态交给 LLM 综合判断。
 *
 * <p>与 {@link ThresholdRouter} 的简单阈值判定不同，LLMRouter 能综合考虑：
 * <ul>
 *   <li>评估分数和各维度评分</li>
 *   <li>检测到的具体问题及其严重程度</li>
 *   <li>当前迭代次数和剩余迭代预算</li>
 *   <li>前一轮反思内容是否给出了可操作的改进建议</li>
 * </ul>
 *
 * <p>LLM 被要求返回 JSON：{@code {"decision": "STOP"|"RETRY"|"FALLBACK", "reasoning": "..."}}。
 * 解析失败时降级为阈值判断（0.7 阈值）。
 */
@Primitive(type = PrimitiveType.ROUTER, name = "llmRouter")
public class LLMRouter implements Router {

    private final ChatFacade chatFacade;
    private final double fallbackThreshold;

    public LLMRouter(ChatFacade chatFacade) {
        this(chatFacade, 0.7);
    }

    public LLMRouter(ChatFacade chatFacade, double fallbackThreshold) {
        this.chatFacade = chatFacade;
        this.fallbackThreshold = fallbackThreshold;
    }

    @Override
    public RouteDecision route(ReflectionContext ctx, Evaluation evaluation, int iteration, int maxIterations) {
        // 空评估直接按阈值兜底
        if (evaluation == null) {
            return iteration >= maxIterations ? RouteDecision.FALLBACK : RouteDecision.RETRY;
        }

        // 第一轮且评分已达标，无需调用 LLM
        if (iteration == 1 && evaluation.isSuccess() && evaluation.getScore() >= 0.85) {
            return RouteDecision.STOP;
        }

        // 已达上限强制终止
        if (iteration >= maxIterations) {
            return RouteDecision.FALLBACK;
        }

        try {
            String prompt = buildRoutingPrompt(ctx, evaluation, iteration, maxIterations);
            ChatRequest request = ChatRequest.builder()
                    .systemPrompt("你是一个路由决策器。请仅返回 JSON，不要包含其他内容。")
                    .messages(List.of(Message.user(prompt)))
                    .temperature(0.2)
                    .build();

            ModelResponse response = chatFacade.chat(request);
            return parseDecision(response.getContent());
        } catch (Exception e) {
            // LLM 不可用时降级为阈值判断
            return fallback(evaluation, iteration, maxIterations);
        }
    }

    @Override
    public RouteDecision routeStream(ReflectionContext ctx, Evaluation evaluation,
                                      int iteration, int maxIterations, Consumer<String> chunkSink) {
        if (evaluation == null) {
            return iteration >= maxIterations ? RouteDecision.FALLBACK : RouteDecision.RETRY;
        }
        if (iteration == 1 && evaluation.isSuccess() && evaluation.getScore() >= 0.85) {
            return RouteDecision.STOP;
        }
        if (iteration >= maxIterations) {
            return RouteDecision.FALLBACK;
        }

        try {
            String prompt = buildRoutingPrompt(ctx, evaluation, iteration, maxIterations);
            StringBuilder full = new StringBuilder();
            chatFacade.chat().prompt()
                    .system("你是一个路由决策器。请仅返回 JSON，不要包含其他内容。")
                    .user(prompt).temperature(0.2)
                    .stream()
                    .doOnNext(chunk -> {
                        if (chunk.getContent() != null) {
                            full.append(chunk.getContent());
                            chunkSink.accept(chunk.getContent());
                        }
                    })
                    .blockLast(Duration.ofMinutes(1));
            return parseDecision(full.toString());
        } catch (Exception e) {
            return fallback(evaluation, iteration, maxIterations);
        }
    }

    /** 组装路由决策 prompt — 包含评估详情、迭代状态和可选反思 */
    private String buildRoutingPrompt(ReflectionContext ctx, Evaluation evaluation,
                                       int iteration, int maxIterations) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下信息做出路由决策。\n\n");
        sb.append("用户请求：").append(StringUtils.truncate(ctx.getUserMessage(), 500)).append("\n\n");
        sb.append("当前输出：").append(StringUtils.truncate(ctx.getCurrentOutput(), 1000)).append("\n\n");
        sb.append("评估分数：").append(String.format("%.2f", evaluation.getScore())).append("\n");
        sb.append("是否成功：").append(evaluation.isSuccess() ? "是" : "否").append("\n");
        sb.append("评估理由：").append(evaluation.getReasoning()).append("\n");

        if (evaluation.getIssues() != null && !evaluation.getIssues().isEmpty()) {
            sb.append("\n检测到的问题：\n");
            evaluation.getIssues().forEach(i ->
                    sb.append("- [").append(i.getSeverity()).append("] ")
                      .append(i.getCategory()).append(": ")
                      .append(i.getDescription()).append("\n"));
        }

        if (ctx.getCurrentReflection() != null && !ctx.getCurrentReflection().isBlank()) {
            sb.append("\n上一轮反思：").append(StringUtils.truncate(ctx.getCurrentReflection(), 800)).append("\n");
        }

        sb.append("\n迭代：").append(iteration).append("/").append(maxIterations).append("\n");
        sb.append("剩余尝试：").append(maxIterations - iteration).append("\n\n");

        sb.append("请返回 JSON 格式的路由决策：\n");
        sb.append("{\"decision\": \"STOP\" | \"RETRY\" | \"FALLBACK\", \"reasoning\": \"决策理由\"}\n\n");
        sb.append("决策指南：\n");
        sb.append("- STOP: 输出已满足要求，可以终止\n");
        sb.append("- RETRY: 输出有改善空间，应进行反思后重试\n");
        sb.append("- FALLBACK: 已达迭代上限或无法改善，终止并返回当前最佳输出\n");

        return sb.toString();
    }

    /** 解析 LLM 返回的 JSON 决策 */
    private RouteDecision parseDecision(String content) {
        if (content == null || content.isBlank()) return RouteDecision.RETRY;

        // 提取 JSON 块或裸 JSON
        String json = content;
        if (content.contains("```json")) {
            int start = content.indexOf("```json") + 7;
            int end = content.indexOf("```", start);
            if (end > start) json = content.substring(start, end).trim();
        }

        String upper = json.toUpperCase();
        if (upper.contains("\"STOP\"")) return RouteDecision.STOP;
        if (upper.contains("\"FALLBACK\"")) return RouteDecision.FALLBACK;
        if (upper.contains("\"RETRY\"")) return RouteDecision.RETRY;

        return RouteDecision.RETRY; // 无法解析时保守重试
    }

    /** 降级阈值判定 */
    private RouteDecision fallback(Evaluation eval, int iteration, int maxIterations) {
        if (eval.isSuccess() && eval.getScore() >= fallbackThreshold) return RouteDecision.STOP;
        if (iteration >= maxIterations) return RouteDecision.FALLBACK;
        return RouteDecision.RETRY;
    }

}
