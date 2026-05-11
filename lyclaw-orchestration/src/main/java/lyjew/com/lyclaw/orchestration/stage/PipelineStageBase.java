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
 * Abstract base for reactive pipeline stages, providing shared utilities.
 */
public abstract class PipelineStageBase implements ReactivePipelineStage {

    protected ServerSentEvent<String> sseEvent(String eventType, String payload) {
        return ServerSentEvent.<String>builder().event(eventType).data(payload).build();
    }

    protected String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

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

    protected lyjew.com.lyclaw.model.ChatRequest buildLlmRequest(ChatContext context, List<String> toolResults) {
        lyjew.com.lyclaw.model.ChatRequest original = context.getRequest();
        List<Message> messages = new ArrayList<>(original.getMessages());

        if (!toolResults.isEmpty()) {
            String ctx = "Previous tool execution results:\n" + String.join("\n", toolResults);
            messages.add(Message.builder().role("user").content(ctx).build());
        }

        return lyjew.com.lyclaw.model.ChatRequest.builder()
                .messages(messages)
                .stream(true)
                .build();
    }

    protected String buildFinalResponse(int successCount, int failCount,
                                         List<String> toolResults, ReflectionReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Orchestration completed.\n");
        sb.append("Tasks executed: ").append(successCount + failCount)
                .append(" (success: ").append(successCount)
                .append(", failed: ").append(failCount).append(")\n");

        if (report != null) {
            sb.append("Reflection score: ").append(String.format("%.2f", report.getOverallScore())).append("\n");
            if (report.getQuality() != null) {
                sb.append("Quality - accuracy: ").append(String.format("%.2f", report.getQuality().getAccuracy()))
                        .append(", completeness: ").append(String.format("%.2f", report.getQuality().getCompleteness()))
                        .append(", safety: ").append(String.format("%.2f", report.getQuality().getSafety()))
                        .append("\n");
            }
        }

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
     * Convenience: retrieve PipelineContext from ChatContext attributes.
     */
    protected PipelineContext getPipelineContext(ChatContext context) {
        return (PipelineContext) context.getAttribute("pipelineContext");
    }
}
