package lyjew.com.lyclaw.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI 模型响应模型，封装了 AI 模型一次生成请求的完整返回数据。
 *
 * 包含生成内容（content）、思考过程（thinking，用于扩展推理模型）、
 * 工具调用请求列表（toolCalls）、结束原因（finishReason）以及令牌使用统计。
 * 内部类 {@link ToolCallRequest} 用于表示单个工具调用请求及其参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelResponse {

    /** 响应唯一标识 */
    private String id;
    /** 模型生成的文本内容 */
    private String content;
    /** 模型的内部思考过程（仅扩展推理模型启用时返回） */
    private String thinking;
    /** 生成此响应的模型名称 */
    private String model;
    /** 模型请求的工具调用列表 */
    private List<ToolCallRequest> toolCalls;
    /** 生成结束原因：stop（正常）、length（超长截断）、tool_calls（请求工具调用）等 */
    private String finishReason;
    /** 本次生成的令牌使用统计 */
    private Usage usage;
    /** 额外的元数据，可存放模型特有的返回字段 */
    private Map<String, Object> metadata;

    /**
     * 判断响应中是否包含工具调用请求。
     *
     * @return true 如果 toolCalls 列表非空
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * 判断响应中是否包含思考过程。
     *
     * @return true 如果 thinking 字段非空
     */
    public boolean hasThinking() {
        return thinking != null && !thinking.isEmpty();
    }

    /**
     * 判断生成是否正常结束。
     *
     * @return true 如果 finishReason 为 "stop"
     */
    public boolean isStopped() {
        return "stop".equals(finishReason);
    }

    /**
     * 判断生成是否因长度限制被截断。
     *
     * @return true 如果 finishReason 为 "length"
     */
    public boolean isTruncated() {
        return "length".equals(finishReason);
    }

    /**
     * 工具调用请求内部类，表示模型在 assistant 消息中发出的单个工具调用。
     *
     * 包含工具调用的唯一 ID、工具名称、JSON 格式的参数字符串和调用顺序索引。
     * 在流式模式下，工具参数可能分多个 chunk 到达，{@link #appendArguments(String)}
     * 方法负责拼接这些碎片化的参数 JSON。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallRequest {
        /** 工具调用的唯一标识 */
        private String id;
        /** 被调用的工具名称 */
        private String name;
        /** JSON 格式的调用参数字符串 */
        private String arguments;
        /** 工具调用在响应中的顺序索引 */
        private int index;

        /**
         * 追加工具调用参数的片段（用于流式场景的参数拼接）。
         *
         * 当参数首次到达时直接赋值；后续追加的片段需要和前一段
         * 进行 JSON 拼接——去掉前一段末尾的 '}' 和当前片段开头的 '{' 后再连接。
         * 这样可以正确组装流式传输中分片到达的 JSON 参数字符串。
         *
         * @param argsFragment 本次到达的参数字段片段（JSON 格式）
         */
        public void appendArguments(String argsFragment) {
            if (argsFragment == null || argsFragment.isEmpty()) return;
            if (this.arguments == null || this.arguments.isEmpty()) {
                this.arguments = argsFragment;
                return;
            }
            String base = this.arguments;
            String frag = argsFragment;
            // 只在末尾 '}' 是 JSON 结构花括号（不在字符串值内）时才去除
            // 避免删除字符串值中的字面花括号（例如 "echo }"）
            if (base.endsWith("}") && !isInsideStringValue(base, base.length() - 1)) {
                base = base.substring(0, base.length() - 1);
            }
            if (frag.startsWith("{") && !isInsideStringValue(frag, 0)) {
                frag = frag.substring(1);
            }
            this.arguments = base + frag;
        }

        /**
         * 判断指定位置是否位于 JSON 字符串值内部。
         * 从字符串开头扫描，跟踪双引号状态（跳过转义引号）。
         */
        private static boolean isInsideStringValue(String s, int pos) {
            boolean inString = false;
            for (int i = 0; i < pos && i < s.length(); i++) {
                char c = s.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++; // 跳过转义字符
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                }
            }
            return inString;
        }
    }
}
