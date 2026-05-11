package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 响应式流水线接口，将多个 {@link ReactivePipelineStage} 实例串联执行。
 *
 * 响应式流水线按阶段优先级顺序依次执行每个阶段，并将所有阶段产生的
 * SSE（Server-Sent Events）事件流拼接成一个统一的 Flux 返回。
 * 阶段之间通过共享的 {@link PipelineContext} 来传递状态和数据。
 * 这是新版推荐的流水线实现，相比旧版 {@link Pipeline} 支持非阻塞的响应式处理。
 */
public interface ReactivePipeline {

    /**
     * 按顺序执行所有阶段，将各阶段产生的 SSE 事件流拼接为单一 Flux。
     *
     * 每个阶段返回一个 SSE 事件的 Flux，流水线按阶段顺序依次订阅这些 Flux，
     * 并将它们连接成一个连续的流返回给调用方。如果某个阶段在 {@link PipelineContext}
     * 中设置了终止标志，后续阶段应返回 {@link Flux#empty()} 来跳过执行。
     *
     * @param context     聊天上下文，包含请求、会话、消息等运行时信息
     * @param pipelineCtx 在阶段间传递的共享可变状态
     * @return 所有阶段的 SSE 事件拼接后的 Flux
     */
    Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx);

    /**
     * 获取流水线中按顺序排列的阶段列表。
     *
     * @return 有序的阶段列表
     */
    List<ReactivePipelineStage> getStages();
}
