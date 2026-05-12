package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * AI 模型适配器核心接口，替代旧 ModelAdapter + Engine 两套体系。
 *
 * <p>流式优先设计——所有调用以 stream() 为核心，call() 是其同步收集特例。
 * 返回结构化 Flux&lt;ModelResponse&gt; 而非原始 SSE 文本，适配器内部完成协议解析。
 *
 * <p>子类需实现 5 个核心方法：provider()、model()、capabilities()、stream()、countTokens()，
 * mergeChunks() 和 validate() 提供默认实现，可按需覆写。
 */
public interface ChatModel {

    /** Provider 名称，对应 @ChatModel.provider */
    String provider();

    /** 当前使用的模型名称 */
    String model();

    /** 此模型支持的能力声明 */
    ModelCapabilities capabilities();

    /**
     * 流式聊天，返回结构化事件流。
     * 每个 ModelResponse 代表一个增量（chunk），调用方无需关心 SSE 解析细节。
     */
    Flux<ModelResponse> stream(ChatRequest request);

    /**
     * 同步聊天——stream() 的阻塞收集特例。
     * 默认实现收集所有 chunk 后调用 mergeChunks() 合并，超时 300 秒。
     */
    default ModelResponse call(ChatRequest request) {
        return stream(request)
                .collectList()
                .map(this::mergeChunks)
                .block(Duration.ofSeconds(300));
    }

    /** 合并多个流式 chunk 为完整响应，子类可覆写实现特定合并逻辑 */
    default ModelResponse mergeChunks(List<ModelResponse> chunks) {
        if (chunks.isEmpty()) {
            return ModelResponse.builder().content("").build();
        }
        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        String finishReason = null;
        String model = null;
        String id = null;
        UsageAccumulator usage = new UsageAccumulator();
        java.util.List<ModelResponse.ToolCallRequest> mergedToolCalls = new java.util.ArrayList<>();

        for (ModelResponse chunk : chunks) {
            if (chunk.getContent() != null) content.append(chunk.getContent());
            if (chunk.getThinking() != null) thinking.append(chunk.getThinking());
            if (chunk.getFinishReason() != null) finishReason = chunk.getFinishReason();
            if (chunk.getModel() != null) model = chunk.getModel();
            if (chunk.getId() != null) id = chunk.getId();
            if (chunk.getUsage() != null) usage.accumulate(chunk.getUsage());
            if (chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty()) {
                for (ModelResponse.ToolCallRequest tc : chunk.getToolCalls()) {
                    mergeToolCall(mergedToolCalls, tc);
                }
            }
        }
        return ModelResponse.builder()
                .id(id).model(model).content(content.toString())
                .thinking(thinking.toString()).finishReason(finishReason)
                .usage(usage.toUsage()).toolCalls(mergedToolCalls.isEmpty() ? null : mergedToolCalls)
                .build();
    }

    /** 计算文本 Token 数量 */
    int countTokens(String text);

    /** 计算消息列表 Token 数量 */
    default int countTokens(List<Message> messages) {
        return messages.stream().mapToInt(m -> countTokens(m.getContent())).sum();
    }

    /** 验证此适配器是否可用（发最小请求验证连通性） */
    default Mono<Boolean> validate() {
        return Mono.just(true);
    }

    // ── 内部工具类 ──

    /** Token 用量累加器，用于 chunk 合并时累加 usage 字段 */
    class UsageAccumulator {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;

        void accumulate(lyjew.com.lyclaw.model.Usage u) {
            promptTokens += u.getPromptTokens();
            completionTokens += u.getCompletionTokens();
            totalTokens += u.getTotalTokens();
        }

        lyjew.com.lyclaw.model.Usage toUsage() {
            return new lyjew.com.lyclaw.model.Usage(promptTokens, completionTokens);
        }
    }

    /** 工具调用合并逻辑：同 index/id 的追加 arguments，无 ID 的追加到最后一条 */
    private void mergeToolCall(java.util.List<ModelResponse.ToolCallRequest> merged,
                               ModelResponse.ToolCallRequest incoming) {
        if (incoming.getId() != null) {
            for (ModelResponse.ToolCallRequest existing : merged) {
                if (incoming.getId().equals(existing.getId())) {
                    if (incoming.getArguments() != null) {
                        existing.appendArguments(incoming.getArguments());
                    }
                    return;
                }
            }
            merged.add(incoming);
        } else if (!merged.isEmpty() && incoming.getArguments() != null) {
            // 无 ID 的 chunk 追加到最后一条已存在的 tool call
            ModelResponse.ToolCallRequest last = merged.get(merged.size() - 1);
            last.appendArguments(incoming.getArguments());
        } else if (incoming.getArguments() != null || incoming.getName() != null) {
            merged.add(incoming);
        }
    }
}
