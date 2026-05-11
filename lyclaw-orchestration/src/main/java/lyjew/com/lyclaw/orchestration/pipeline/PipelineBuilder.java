package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineContext;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.pipeline.ReactivePipeline;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 管线构建器。
 *
 * 同时管理两套管线的构建：
 * 1. 同步管线 (Pipeline) —— 旧版，基于 Chain 的阻塞式执行
 * 2. 响应式管线 (ReactivePipeline) —— 新版，基于 Flux 的非阻塞串联执行
 *
 * 自动发现 Spring 容器中所有 PipelineStage 和 ReactivePipelineStage 实现，
 * 按 getOrder() 排序后构建管线。支持 rebuild 操作重新构建。
 */
@Slf4j
@Component
public class PipelineBuilder {

    /** 所有同步管线阶段（已排序） */
    private final List<PipelineStage> allStages;
    /** 所有响应式管线阶段（已排序） */
    private final List<ReactivePipelineStage> reactiveStages;
    /** 缓存的同步管线 */
    private Pipeline pipeline;
    /** 缓存的响应式管线 */
    private ReactivePipeline reactivePipeline;

    /**
     * 构造时自动发现并排序所有阶段实现，构建两种管线。
     *
     * @param allStages       Spring 注入的所有同步 PipelineStage
     * @param reactiveStages  Spring 注入的所有 ReactivePipelineStage
     */
    public PipelineBuilder(List<PipelineStage> allStages,
                           List<ReactivePipelineStage> reactiveStages) {
        // 同步阶段按 order 排序
        List<PipelineStage> sorted = new ArrayList<>(allStages);
        sorted.sort(Comparator.comparingInt(PipelineStage::getOrder));
        this.allStages = sorted;
        this.pipeline = new DefaultPipeline(new ArrayList<>(sorted));
        log.info("[PipelineBuilder] Auto-discovered {} PipelineStage(s), built Pipeline:", allStages.size());
        for (PipelineStage stage : allStages) {
            log.info("  -- [{}] order={}", stage.getStageName(), stage.getOrder());
        }

        // 响应式阶段按 order 排序
        List<ReactivePipelineStage> sortedReactive = new ArrayList<>(reactiveStages);
        sortedReactive.sort(Comparator.comparingInt(ReactivePipelineStage::getOrder));
        this.reactiveStages = sortedReactive;
        this.reactivePipeline = new DefaultReactivePipeline(sortedReactive);
        log.info("[PipelineBuilder] Auto-discovered {} ReactivePipelineStage(s), built ReactivePipeline:", reactiveStages.size());
        for (ReactivePipelineStage stage : reactiveStages) {
            log.info("  -- [{}] order={}", stage.getStageName(), stage.getOrder());
        }
    }

    /** @deprecated 请使用 {@link #buildReactive()} 获取新版响应式管线 */
    @Deprecated
    public Pipeline build() {
        return pipeline;
    }

    /** @deprecated 请使用 {@link #rebuildReactive()} 重新构建新版管线 */
    @Deprecated
    public Pipeline rebuild() {
        List<PipelineStage> sorted = new ArrayList<>(allStages);
        sorted.sort(Comparator.comparingInt(PipelineStage::getOrder));
        this.pipeline = new DefaultPipeline(sorted);
        log.info("[PipelineBuilder] Pipeline rebuilt with {} stages", sorted.size());
        return pipeline;
    }

    /**
     * @return 当前缓存的响应式管线
     */
    public ReactivePipeline buildReactive() {
        return reactivePipeline;
    }

    /**
     * 重新构建响应式管线（用于阶段列表动态更新场景）。
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

    /** @return 同步阶段列表副本 */
    public List<PipelineStage> getStages() {
        return new ArrayList<>(allStages);
    }

    /** @return 响应式阶段列表副本 */
    public List<ReactivePipelineStage> getReactiveStages() {
        return new ArrayList<>(reactiveStages);
    }

    /** @return 同步阶段数量 */
    public int getStageCount() {
        return allStages.size();
    }

    /** @return 响应式阶段数量 */
    public int getReactiveStageCount() {
        return reactiveStages.size();
    }

    /**
     * 响应式管线的默认实现。
     *
     * 核心策略：将各阶段的 Flux 按顺序拼接(concatWith)。
     * 每个阶段内部通过 Flux.defer() 延迟执行，确保前一个阶段完成后再启动下一个。
     * 阶段内部可通过 PipelineContext.isTerminated() 检查是否需要提前终止。
     */
    private static class DefaultReactivePipeline implements ReactivePipeline {

        private final List<ReactivePipelineStage> stages;

        DefaultReactivePipeline(List<ReactivePipelineStage> stages) {
            this.stages = List.copyOf(stages);  // 不可变拷贝
        }

        /**
         * 按顺序串联执行所有阶段。
         * 使用 concatWith 保证顺序性（前一个 Flux complete 后才接下一个）。
         *
         * @param context     聊天上下文
         * @param pipelineCtx 管线上下文（用于终止信号传递）
         * @return 串联后的 SSE 事件流
         */
        @Override
        public Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx) {
            Flux<ServerSentEvent<String>> result = Flux.empty();
            for (ReactivePipelineStage stage : stages) {
                // defer 确保每次订阅都重新评估上下文状态（如 isTerminated）
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
