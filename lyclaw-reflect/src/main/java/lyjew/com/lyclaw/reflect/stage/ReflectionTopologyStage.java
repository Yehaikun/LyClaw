package lyjew.com.lyclaw.reflect.stage;

import static lyjew.com.lyclaw.react.ContextKeys.REFLECTION_SUMMARY;
import static lyjew.com.lyclaw.react.ContextKeys.TASK_SUMMARY;
import static lyjew.com.lyclaw.react.SseEventTypes.DONE;
import static lyjew.com.lyclaw.react.SseEventTypes.MESSAGE;
import static lyjew.com.lyclaw.react.SseEventTypes.REFLECT_ERROR;
import static lyjew.com.lyclaw.react.SseEventTypes.REFLECT_STEP;
import static lyjew.com.lyclaw.react.SseEventTypes.REFLECT_SUMMARY;
import static lyjew.com.lyclaw.react.SseEventTypes.TOOL_APPROVAL;
import static lyjew.com.lyclaw.react.SseEventTypes.TOOL_CALL;

import lyjew.com.lyclaw.annotation.PipelineStage;
import lyjew.com.lyclaw.pipeline.BuiltInProfiles;
import lyjew.com.lyclaw.pipeline.PipelineProfile;
import lyjew.com.lyclaw.pipeline.stage.MetricsStage;
import lyjew.com.lyclaw.pipeline.stage.PipelineStageBase;
import lyjew.com.lyclaw.pipeline.stage.SecurityCheckStage;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.reflect.impl.TopologyExecutor;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.registry.ReflectionTopologyRegistry;
import lyjew.com.lyclaw.reflect.topology.*;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 反思拓扑管线阶段 — 替代原 RespondStage + ReflectionStage，以可组合的图结构替代硬编码反思循环。
 *
 * <p>执行流程（异步非阻塞）：
 * <ol>
 *   <li>根据 agentId 从 {@link ReflectionTopologyRegistry} 查找拓扑，未命中时回退到默认拓扑</li>
 *   <li>将 {@link AgentContext} 的请求级字段映射到 {@link ReflectionContext}</li>
 *   <li>委托 {@link TopologyExecutor} 遍历 DAG 执行原语链（提交到 boundedElastic 异步执行）</li>
 *   <li>通过 {@link Sinks.Many} 管道将 {@link TopologyEvent} 即时转为 SSE 事件流</li>
 * </ol>
 *
 * <p>使用 Sinks.Many + tryEmitNext 替代 Flux.create + sink.next，
 * 消除同步阻塞导致的背压死锁和事件批量输出问题。
 *
 * <p>异常处理：InterruptedException 视为正常取消；其他异常捕获后 emit reflect_error 事件。
 *
 * @see ReflectionTopologyRegistry
 * @see TopologyExecutor
 */
@PipelineStage(name = "ReflectionTopology", after = SecurityCheckStage.class, before = MetricsStage.class, group = "CORE")
public class ReflectionTopologyStage extends PipelineStageBase {

    private final ReflectionTopologyRegistry topologyRegistry;
    private final TopologyExecutor topologyExecutor;

    public ReflectionTopologyStage(ReflectionTopologyRegistry topologyRegistry,
                                    TopologyExecutor topologyExecutor) {
        this.topologyRegistry = topologyRegistry;
        this.topologyExecutor = topologyExecutor;
    }

    @Override
    public int getOrder() {
        return 3;
    }

    @Override
    public String getStageName() {
        return "ReflectionTopology";
    }

    @Override
    public boolean supportsProfile(PipelineProfile profile) {
        return profile == BuiltInProfiles.REFLECTION;
    }

    @Override
    public boolean isExecutionStage() {
        return true;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext agentCtx) {
        if (agentCtx.isTerminated()) {
            return Flux.empty();
        }

        String agentId = agentCtx.getAgentId();

        // 使用 Sinks.Many 作为非阻塞事件管道
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast()
                .onBackpressureBuffer();

        // 异步提交拓扑执行到 boundedElastic，避免阻塞调用者。保存 Disposable 以便下游取消时释放线程
        var monoDisposable = Mono.fromCallable(() -> {
            try {
                ReflectionTopology topology = topologyRegistry.resolve(agentId)
                        .orElseGet(() -> topologyRegistry.getDefaultTopology());
                ReflectionContext reflectCtx = buildReflectionContext(agentCtx);

                var persistSink = agentCtx.getTopologyEventSink();

                ExecutionResult execResult = topologyExecutor.execute(topology, reflectCtx, event -> {
                    ServerSentEvent<String> sse = convertToSSE(event);
                    if (sse != null) {
                        sink.tryEmitNext(sse);
                    }
                    if (persistSink != null && !event.getType().isStreamingChunk()) {
                        persistSink.accept(event);
                    }
                });

                // 发射反思摘要供前端持久化展示
                Object summary = reflectCtx.getAttribute(REFLECTION_SUMMARY);
                if (summary != null) {
                    sink.tryEmitNext(sseEvent(REFLECT_SUMMARY, Map.of("summary", summary)));
                }

                // 最终输出作为正常 message 事件发出，让前端渲染到聊天区域
                String finalOutput = execResult.getFinalOutput();
                if (finalOutput != null && !finalOutput.isBlank()) {
                    sink.tryEmitNext(sseEvent(MESSAGE, finalOutput));
                }

                sink.tryEmitNext(sseEvent(DONE, ""));
                sink.tryEmitComplete();
            } catch (Exception err) {
                if (isInterrupted(err)) {
                    log.info("ReflectionTopology 被取消 agent={}", agentId);
                } else {
                    log.error("ReflectionTopology 执行失败 agent={}", agentId, err);
                }
                sink.tryEmitNext(sseEvent(REFLECT_ERROR, Map.of(
                        "error", err.getMessage() != null ? err.getMessage() : "未知错误"
                )));
                sink.tryEmitNext(sseEvent(DONE, ""));
                sink.tryEmitComplete();
            }
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();

        return sink.asFlux().doOnCancel(() -> monoDisposable.dispose());
    }

    /** 检查异常是否为中断/取消导致的 */
    private boolean isInterrupted(Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof InterruptedException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    /** 将 TopologyEvent 转为 SSE ServerSentEvent，工具调用事件作为独立 SSE 事件发出 */
    private ServerSentEvent<String> convertToSSE(TopologyEvent event) {
        // 工具调用事件：作为独立 SSE event:tool_call 发出，让前端 onToolCall 回调处理
        if (event.getType() == TopologyEventType.ACTOR_CHUNK) {
            String chunkType = (String) event.getData().get("chunkType");
            if (TOOL_CALL.equals(chunkType)) {
                return sseEvent(TOOL_CALL, (String) event.getData().get("text"));
            }
            if (TOOL_APPROVAL.equals(chunkType)) {
                return sseEvent(TOOL_APPROVAL, (String) event.getData().get("text"));
            }
        }

        // 其余事件统一转为 reflect_step
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", event.getType().name());
        if (event.getNodeId() != null) data.put("nodeId", event.getNodeId());
        if (event.getPrimitiveType() != null) data.put("primitiveType", event.getPrimitiveType());
        data.put("iteration", event.getIteration());
        if (event.getDurationMs() > 0) data.put("durationMs", event.getDurationMs());
        if (event.getData() != null && !event.getData().isEmpty()) {
            data.putAll(event.getData());
        }
        return sseEvent(REFLECT_STEP, data);
    }

    /** 将管线 AgentContext 映射为拓扑执行所需的 ReflectionContext */
    private ReflectionContext buildReflectionContext(AgentContext agentCtx) {
        ReflectionContext ctx = new ReflectionContext();
        ctx.setAgentId(agentCtx.getAgentId());
        ctx.setSessionId(agentCtx.getSessionId());
        ctx.setUserMessage(agentCtx.getUserMessage());
        ctx.setSystemPrompt(agentCtx.getSystemPrompt());

        if (agentCtx.getRunMetadata("userId") != null) {
            ctx.setUserId(String.valueOf(agentCtx.getRunMetadata("userId")));
        }
        ctx.setChatRequest(agentCtx.getChatRequest());

        // PlanExecutionStage 可能已产出的任务摘要，转入拓扑上下文供 Evaluator 使用
        Object taskSummary = agentCtx.getAttribute(TASK_SUMMARY);
        if (taskSummary != null) {
            ctx.setTaskSummary(taskSummary.toString());
        }

        return ctx;
    }
}
