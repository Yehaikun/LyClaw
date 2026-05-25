package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.react.AgentContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 响应式流水线阶段接口。
 *
 * <p>每个阶段接收统一的 {@link AgentContext}，返回 SSE 事件流。
 * 阶段在执行前应检查 {@link AgentContext#isTerminated()}，
 * 若为 true 则返回 {@link Flux#empty()} 跳过当前阶段。</p>
 *
 * <p>阶段通过 {@link #getOrder()} 返回值决定执行顺序，数值越小越先执行。</p>
 */
public interface ReactivePipelineStage {

    /**
     * 执行当前阶段的处理逻辑，产生 SSE 事件流。
     *
     * @param ctx Agent 上下文（聚合了流水线状态和聊天上下文的全部字段）
     * @return 本阶段产生的 SSE 事件流；若流水线已终止则返回空 Flux
     */
    Flux<ServerSentEvent<String>> execute(AgentContext ctx);

    /**
     * 获取阶段的执行顺序号。
     * @return 序号，数值越小越先执行
     */
    int getOrder();

    /**
     * 获取阶段的可读名称。
     * @return 阶段名称
     */
    String getStageName();

    /**
     * 判断此阶段是否支持指定的运行模式。
     * @param profile 运行模式
     * @return true 表示支持（默认）
     */
    default boolean supportsProfile(PipelineProfile profile) { return true; }

    /**
     * 判断此阶段是否为执行型阶段（影响管线流程选择）。
     * @return true 表示是执行型阶段
     */
    default boolean isExecutionStage() { return false; }
}
