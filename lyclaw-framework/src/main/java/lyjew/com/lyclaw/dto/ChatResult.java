package lyjew.com.lyclaw.dto;

import lyjew.com.lyclaw.tool.ToolResult;

import java.util.List;

/**
 * 聊天结果 DTO，封装一次 LLM 对话完成后的完整输出信息。
 *
 * <p>包含生成文本内容、完成原因、Token 使用量、工具调用结果列表和执行耗时。</p>
 */
public class ChatResult {

    /** LLM 生成的文本内容 */
    private String content;
    /** 完成原因（如 stop、length、tool_calls） */
    private String finishReason;
    /** Token 使用量摘要 */
    private String tokenUsage;
    /** 本次对话中的工具调用结果列表 */
    private List<ToolResult> toolResults;
    /** 总执行耗时（毫秒） */
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
