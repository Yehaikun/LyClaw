package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.pipeline.ReactivePipeline;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 响应式管线构建器。
 *
 * <p>自动发现 Spring 容器中所有 {@link ReactivePipelineStage} 实现，
 * 按 getOrder() 排序后构建 {@link ReactivePipeline}。支持 rebuildReactive() 动态重建。</p>
 *
 * <p>管线执行策略：各阶段 Flux 按顺序 concatWith 串联，通过 Flux.defer()
 * 延迟订阅确保前一阶段完成后才启动下一阶段。</p>
 *
 * @see ReactivePipelineStage
 */
@Slf4j
@Component
public class PipelineBuilder {

    /** 所有响应式管线阶段（已按 order 排序） */
    private final List<ReactivePipelineStage> reactiveStages;
    /** 缓存的响应式管线 */
    private ReactivePipeline reactivePipeline;

    /**
     * 构造时自动发现并排序所有响应式阶段实现。
     *
     * @param reactiveStages Spring 注入的所有 ReactivePipelineStage
     */
    public PipelineBuilder(List<ReactivePipelineStage> reactiveStages) {
        List<ReactivePipelineStage> sorted = new ArrayList<>(reactiveStages);
        sorted.sort(Comparator.comparingInt(ReactivePipelineStage::getOrder));
        this.reactiveStages = sorted;
        this.reactivePipeline = new DefaultReactivePipeline(sorted);
        log.info("[PipelineBuilder] Auto-discovered {} ReactivePipelineStage(s):", reactiveStages.size());
        for (ReactivePipelineStage stage : reactiveStages) {
            log.info("  -- [{}] order={}", stage.getStageName(), stage.getOrder());
        }
    }

    /**
     * @return 当前缓存的响应式管线
     */
    public ReactivePipeline buildReactive() {
        return reactivePipeline;
    }

    /**
     * 重新构建响应式管线（阶段列表变更后调用）。
     *
     * @return 重新构建的管线
     */
    public ReactivePipeline rebuildReactive() {
        List<ReactivePipelineStage> sorted = new ArrayList<>(reactiveStages);
        sorted.sort(Comparator.comparingInt(ReactivePipelineStage::getOrder));
        this.reactivePipeline = new DefaultReactivePipeline(sorted);
        log.info("[PipelineBuilder] ReactivePipeline rebuilt with {} stages", sorted.size());
        return reactivePipeline;
    }

    /** @return 响应式阶段列表副本 */
    public List<ReactivePipelineStage> getReactiveStages() {
        return new ArrayList<>(reactiveStages);
    }

    /** @return 响应式阶段数量 */
    public int getReactiveStageCount() {
        return reactiveStages.size();
    }

    /**
     * 响应式管线的默认实现——各阶段按 order 顺序 concatWith 串联。
     */
    private static class DefaultReactivePipeline implements ReactivePipeline {

        private final List<ReactivePipelineStage> stages;

        DefaultReactivePipeline(List<ReactivePipelineStage> stages) {
            this.stages = List.copyOf(stages);
        }

        @Override
        public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
            Flux<ServerSentEvent<String>> result = Flux.empty();
            for (ReactivePipelineStage stage : stages) {
                result = result.concatWith(
                        Flux.defer(() -> stage.execute(context, pipelineCtx))
                );
            }
            return result;
        }

        @Override
        public List<ReactivePipelineStage> getStages() {
            return stages;
        }
    }
}
