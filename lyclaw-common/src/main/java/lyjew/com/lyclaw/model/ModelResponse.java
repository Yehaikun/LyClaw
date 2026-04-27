package lyjew.com.lyclaw.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 模型统一响应——所有厂商的返回值都转成这个
 *
 * 这是模型抽象层对上层暴露的统一数据结构，
 * 无论底层调用的是 MiniMax、DeepSeek 还是其他厂商，
 * 最终都转成这个对象返回给调用方
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelResponse {

    /** 本次响应的唯一标识，用于追踪和日志 */
    private String id;

    /** AI 回复的文本内容。
     *  finishReason = "stop" 时有值，
     *  finishReason = "tool_calls" 时为空（工具调用请求在 toolCalls 中） */
    private String content;

    /** 模型的思考过程内容。
     *  部分厂商（MiniMax、DeepSeek Anthropic 格式）支持返回思考链，
     *  不支持的厂商此字段为 null */
    private String thinking;

    /** 实际使用的模型名称，如 "MiniMax-M2.7"、"deepseek-v4-pro" */
    private String model;

    /** 工具调用请求列表。
     *  finishReason = "tool_calls" 时有值，
     *  finishReason = "stop" 时为 null */
    private List<ToolCallRequest> toolCalls;

    /** 停止原因，可能的取值：
     *  - "stop"           : 模型自然结束
     *  - "tool_calls"     : 模型请求调用工具
     *  - "length"         : 达到 max_tokens 上限
     *  - "content_filter" : 内容被安全策略过滤 */
    private String finishReason;

    /** Token 用量统计 */
    private Usage usage;

    /** 厂商特有字段兜底，如 MiniMax 的 base_resp、input_sensitive 等。
     *  上层业务一般不需要关心，主要用于 debug 和日志 */
    private Map<String, Object> metadata;

    // ========== 便捷判断方法 ==========

    /** 是否需要调用工具 */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** 是否有思考过程 */
    public boolean hasThinking() {
        return thinking != null && !thinking.isEmpty();
    }

    /** 是否正常结束 */
    public boolean isStopped() {
        return "stop".equals(finishReason);
    }

    /** 是否因为达到长度上限而截断 */
    public boolean isTruncated() {
        return "length".equals(finishReason);
    }

    // ========== 内部类 ==========

    /**
     * 模型返回的工具调用请求
     * "我要调哪个函数、参数是什么"
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallRequest {
        /** 本次工具调用的唯一 ID，后续 tool 消息需要回传这个 ID */
        private String id;
        /** 要调用的函数名 */
        private String name;
        /** 函数参数，JSON 字符串格式 */
        private String arguments;
    }
}