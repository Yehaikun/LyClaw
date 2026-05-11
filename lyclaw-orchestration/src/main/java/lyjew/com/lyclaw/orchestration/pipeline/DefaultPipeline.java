package lyjew.com.lyclaw.orchestration.pipeline;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

/**
 * 默认同步管线实现。
 *
 * 持有按顺序排列的 PipelineStage 列表，执行时创建 DefaultChain
 * 并驱动所有阶段依次执行。阶段列表在构造时做不可变拷贝，
 * 确保线程安全。
 */
@Slf4j
public class DefaultPipeline implements Pipeline {

    private final List<PipelineStage> stages;

    DefaultPipeline(List<PipelineStage> stages) {
        this.stages = List.copyOf(stages);  // 不可变拷贝
        log.info("[DefaultPipeline] Initialized with {} stages", stages.size());
    }

    /**
     * 创建 DefaultChain 并驱动所有阶段依次执行。
     *
     * @param context 聊天上下文
     */
    @Override
    public void execute(ChatContext context) {
        new DefaultChain(stages, 0).proceed(context);
    }

    @Override
    public List<PipelineStage> getStages() {
        return stages;
    }
}
