package lyjew.com.lyclaw.dto;

import lyjew.com.lyclaw.tool.ToolResult;

import java.util.List;

public class ChatResult {

    private String content;
    private String finishReason;
    private String tokenUsage;
    private List<ToolResult> toolResults;
    private long durationMs;

    public ChatResult(String content, String finishReason, String tokenUsage,
                      List<ToolResult> toolResults, long durationMs) {
        this.content = content;
        this.finishReason = finishReason;
        this.tokenUsage = tokenUsage;
        this.toolResults = toolResults;
        this.durationMs = durationMs;
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }

    public String getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(String tokenUsage) { this.tokenUsage = tokenUsage; }

    public List<ToolResult> getToolResults() { return toolResults; }
    public void setToolResults(List<ToolResult> toolResults) { this.toolResults = toolResults; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}
