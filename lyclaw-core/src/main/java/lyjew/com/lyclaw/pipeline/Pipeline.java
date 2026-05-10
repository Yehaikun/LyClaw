package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 管道编排入口 —— 将对话处理分解为多个可编排的阶段（PipelineStage）。
 *
 * <p>Pipeline 按 {@code getOrder()} 顺序依次执行每个 {@link PipelineStage} 的
 * {@code process()} 方法，结果通过 {@link ChatContext} 在各阶段间传递。</p>
 *
 * <p><b>为什么是接口</b>：不同场景可能需要不同的管道执行策略：
 * <ul>
 *   <li>{@link lyjew.com.lyclaw.pipeline.impl.DefaultPipeline} — 默认实现，同步遍历所有 Stage</li>
 *   <li>AsyncPipeline — 允许异步执行 Stage（未来）</li>
 *   <li>ParallelPipeline — 允许并行执行无依赖关系的 Stage（未来）</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see PipelineStage
 * @see lyjew.com.lyclaw.pipeline.impl.DefaultPipeline
 */
public interface Pipeline {

    /**
     * 执行整个管道。按 {@code getOrder()} 顺序依次执行每个 Stage 的 process()，
     * 结果通过 {@link ChatContext} 在各阶段间传递。
     *
     * @param context 包含请求、会话、记忆的完整上下文
     */
    void execute(ChatContext context);

    /**
     * 获取当前管道的所有阶段列表。
     *
     * @return 按执行顺序排列的阶段列表（不可变视图）
     */
    List<PipelineStage> getStages();
}
