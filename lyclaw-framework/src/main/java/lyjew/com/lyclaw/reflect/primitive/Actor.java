package lyjew.com.lyclaw.reflect.primitive;

import lyjew.com.lyclaw.reflect.model.ActorResult;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import org.springframework.http.codec.ServerSentEvent;

import java.util.function.Consumer;

/**
 * Actor 原语 — 根据上下文生成输出。
 *
 * <p>实现类应优先重写 {@link #executeStream(ReflectionContext, Consumer)} 以支持
 * 逐块流式推送（LLM 生成内容、工具调用事件），默认回退到 {@link #execute(ReflectionContext)}。
 */
@FunctionalInterface
public interface Actor extends ReflectionPrimitive {
    ActorResult execute(ReflectionContext ctx);

    /**
     * 流式执行 Actor — 在执行过程中通过 chunkSink 推送中间事件（文本块、工具调用），
     * 完成后返回汇总结果。默认实现回退到同步 {@link #execute} 不产生中间事件。
     */
    default ActorResult executeStream(ReflectionContext ctx, Consumer<ServerSentEvent<String>> chunkSink) {
        return execute(ctx);
    }
}
