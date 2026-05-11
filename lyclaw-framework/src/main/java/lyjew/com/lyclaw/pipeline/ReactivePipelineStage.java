package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 响应式流水线阶段接口，是已废弃的 {@link PipelineStage} 的响应式替代。
 *
 * 每个阶段返回一个 {@link Flux} 类型的 SSE 事件流，流水线会将所有阶段的事件流
 * 拼接成一个连续的流。阶段在执行前应检查 {@link PipelineContext#isTerminated()}
 * 是否被设置为 true，如果是则直接返回 {@link Flux#empty()} 来跳过当前阶段，
 * 这样可以实现流水线的早期终止机制。
 * <p>
 * 阶段通过 {@link #getOrder()} 返回值来决定执行顺序，数值越小的阶段越先执行。
 */
public interface ReactivePipelineStage {

    /**
     * 执行当前阶段的处理逻辑，产生 SSE 事件流。
     *
     * 实现类应在此方法中完成本阶段的业务处理，并将处理过程中的关键事件
     * 以 SSE 格式逐条发送到返回的 Flux 中。如果流水线在上游已被终止，
     * 则返回空的 Flux。
     *
     * @param context     聊天上下文（包含请求、会话、追踪等信息）
     * @param pipelineCtx 在阶段间传递的共享可变状态
     * @return 本阶段产生的 SSE 事件流；若流水线已终止则返回空 Flux
     */
    Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx);

    /**
     * 获取阶段的执行顺序号。
     *
     * @return 序号，数值越小越先执行
     */
    int getOrder();

    /**
     * 获取阶段的可读名称，用于日志输出和运行状态检查。
     *
     * @return 阶段名称
     */
    String getStageName();
}
