package lyjew.com.lyclaw.dto;

import lyjew.com.lyclaw.tool.ToolResult;

import java.util.List;

/**
 * 模型对话结果 —— Engine.execute() 的统一返回值 DTO。
 *
 * <p>当 Pipeline 完成一次完整的对话处理后（包含模型调用、工具调用循环），
 * 所有产出结果被封装为这个对象返回给上层调用者（Controller / WebSocket Handler）。</p>
 *
 * <p><b>设计动机</b>：将 AI 回复的文本内容、工具调用结果、Token 用量、
 * 以及请求耗时打包为一个不可变对象，避免逐字段传递。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>DefaultEngine.execute() 的返回值类型</li>
 *   <li>Interceptor.postHandle() 的回调参数</li>
 *   <li>HTTP Controller 将 ChatResult 序列化为 JSON 返回给前端</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class ChatResult {

    /** AI 回复的文本内容。当模型没有文本回复（只有工具调用请求）时可能为 null */
    private final String content;

    /**
     * 完成原因 —— 描述对话处理的终止状态。
     * <ul>
     *   <li>"stop" — 模型正常结束回复</li>
     *   <li>"error" — 处理过程中发生了不可恢复的错误</li>
     *   <li>"timeout" — 管道执行超时</li>
     * </ul>
     */
    private final String finishReason;

    /** Token 用量摘要，格式如 "prompt=123 completion=45 total=168" */
    private final String tokenUsage;

    /** 工具调用结果列表。如果没有工具调用，为空列表（非 null） */
    private final List<ToolResult> toolResults;

    /** 请求耗时（毫秒）。从 execute() 调用到返回结果的总耗时 */
    private final long durationMs;

    /**
     * 构造一个 ChatResult 实例。
     *
     * @param content     AI 回复的文本内容
     * @param finishReason 完成原因
     * @param tokenUsage  Token 用量摘要
     * @param toolResults 工具调用结果列表
     * @param durationMs  请求耗时（毫秒）
     */
    public ChatResult(String content, String finishReason, String tokenUsage,
                      List<ToolResult> toolResults, long durationMs) {
        this.content = content;
        this.finishReason = finishReason;
        this.tokenUsage = tokenUsage;
        this.toolResults = toolResults;
        this.durationMs = durationMs;
    }

    /** @return AI 回复的文本内容 */
    public String getContent() { return content; }

    /** @return 完成原因 */
    public String getFinishReason() { return finishReason; }

    /** @return Token 用量摘要 */
    public String getTokenUsage() { return tokenUsage; }

    /** @return 工具调用结果列表 */
    public List<ToolResult> getToolResults() { return toolResults; }

    /** @return 请求耗时（毫秒） */
    public long getDurationMs() { return durationMs; }
}