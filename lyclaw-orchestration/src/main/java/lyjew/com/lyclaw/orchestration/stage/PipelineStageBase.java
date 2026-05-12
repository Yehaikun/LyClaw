package lyjew.com.lyclaw.orchestration.stage;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 响应式管线阶段的抽象基类，提供公共工具方法。
 *
 * 子类通过继承获得以下能力：
 * - SSE 事件构建（sseEvent）
 * - JSON 字符串转义（escapeJson）
 * - 结构化 JSON 日志输出（logJson）
 * - LLM 请求构建（buildLlmRequest，将工具结果合并到消息中）
 * - 最终响应文本构建（buildFinalResponse，含反思报告摘要）
 * - PipelineContext 获取（getPipelineContext）
 *
 * 所有子类需要使用 @PipelineStage 注解声明阶段名称、执行顺序和分组。
 */
public abstract class PipelineStageBase implements ReactivePipelineStage {

    /**
     * 构建 SSE 事件。
     *
     * @param eventType 事件类型（如 "message"、"error"、"done"）
     * @param payload   负载数据
     * @return ServerSentEvent 实例
     */
    protected ServerSentEvent<String> sseEvent(String eventType, String payload) {
        return ServerSentEvent.<String>builder().event(eventType).data(payload).build();
    }

    /**
     * JSON 字符串值转义，防止注入。
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    protected String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 构建结构化的 JSON 日志字符串，便于日志系统解析。
     *
     * @param level      日志级别（INFO/WARN/ERROR）
     * @param event      事件名称（如 stage_start、stage_complete、feign_call）
     * @param stage      阶段名称
     * @param traceId    追踪 ID
     * @param message    日志消息
     * @param durationMs 耗时毫秒（可为 null）
     * @return JSON 格式的日志字符串
     */
    protected String logJson(String level, String event, String stage, String traceId,
                              String message, Long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestamp\":\"").append(Instant.now().toString()).append("\"");
        sb.append(",\"level\":\"").append(level).append("\"");
        sb.append(",\"event\":\"").append(event).append("\"");
        sb.append(",\"stage\":\"").append(stage).append("\"");
        sb.append(",\"traceId\":\"").append(traceId).append("\"");
        sb.append(",\"message\":\"").append(escapeJson(message)).append("\"");
        if (durationMs != null) {
            sb.append(",\"durationMs\":").append(durationMs);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 构建发送给 LLM 的请求。
     * 将原始消息和工具执行结果合并，始终使用流式模式。
     *
     * @param context     聊天上下文
     * @param toolResults 工具执行结果列表
     * @return 组装好的 ChatRequest
     */
    protected lyjew.com.lyclaw.model.ChatRequest buildLlmRequest(ChatContext context, List<String> toolResults) {
        lyjew.com.lyclaw.model.ChatRequest original = context.getRequest();
        List<Message> messages = new ArrayList<>(original.getMessages());

        // 如果有工具执行结果，以 user 角色消息追加到对话中
        if (!toolResults.isEmpty()) {
            String ctx = "Previous tool execution results:\n" + String.join("\n", toolResults);
            messages.add(Message.builder().role("user").content(ctx).build());
        }

        return lyjew.com.lyclaw.model.ChatRequest.builder()
                .messages(messages)
                .stream(true)
                .tools(original.getTools())
                .toolChoice(original.getToolChoice() != null ? original.getToolChoice() : "auto")
                .build();
    }

    /**
     * 构建最终响应文本。
     * 包含任务执行摘要、反思评分、质量指标和前 5 条工具结果。
     *
     * @param successCount 成功任务数
     * @param failCount    失败任务数
     * @param toolResults  工具结果列表
     * @param report       反思报告（可为 null）
     * @return 格式化的响应文本
     */
    protected String buildFinalResponse(int successCount, int failCount,
                                         List<String> toolResults, ReflectionReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Orchestration completed.\n");
        sb.append("Tasks executed: ").append(successCount + failCount)
                .append(" (success: ").append(successCount)
                .append(", failed: ").append(failCount).append(")\n");

        // 反思报告摘要
        if (report != null) {
            sb.append("Reflection score: ").append(String.format("%.2f", report.getOverallScore())).append("\n");
            if (report.getQuality() != null) {
                sb.append("Quality - accuracy: ").append(String.format("%.2f", report.getQuality().getAccuracy()))
                        .append(", completeness: ").append(String.format("%.2f", report.getQuality().getCompleteness()))
                        .append(", safety: ").append(String.format("%.2f", report.getQuality().getSafety()))
                        .append("\n");
            }
        }

        // 工具结果摘要（最多展示 5 条，每条截断到 200 字符）
        if (!toolResults.isEmpty()) {
            sb.append("\nResults summary:\n");
            for (int i = 0; i < Math.min(toolResults.size(), 5); i++) {
                String result = toolResults.get(i);
                sb.append("  [").append(i + 1).append("] ")
                        .append(result.length() > 200 ? result.substring(0, 200) + "..." : result)
                        .append("\n");
            }
            if (toolResults.size() > 5) {
                sb.append("  ... and ").append(toolResults.size() - 5).append(" more results\n");
            }
        }

        return sb.toString();
    }

    /**
     * 从 ChatContext 属性中获取 PipelineContext。
     *
     * @param context 聊天上下文
     * @return PipelineContext 实例
     */
    protected PipelineContext getPipelineContext(ChatContext context) {
        return (PipelineContext) context.getAttribute("pipelineContext");
    }
}
