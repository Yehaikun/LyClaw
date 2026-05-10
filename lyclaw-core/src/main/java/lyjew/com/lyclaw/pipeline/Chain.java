package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 阶段链控制器 —— 控制 PipelineStage 之间的流转。
 *
 * <p>Stage 在 process() 中通过 Chain 控制执行流程走向：
 * <ul>
 *   <li>默认调 {@link #next(ChatContext)} 继续执行下一个 Stage</li>
 *   <li>需中断时调 {@link #breakChain(ChatContext)} 跳过后续所有 Stage</li>
 * </ul>
 * </p>
 *
 * <p><b>为什么保留 Chain</b>：当前 DefaultPipeline 直接 for 循环遍历，
 * 不依赖 Chain 的流转控制。但作为接口声明，Chain 保留了每个 Stage
 * "有权决定流程走向"的能力——未来 AsyncPipeline、分支 Pipeline
 * 等实现可能依赖此控制权。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see PipelineStage
 */
public interface Chain {

    /**
     * 将控制权传递给链中的下一个 PipelineStage。
     *
     * @param context 对话上下文
     * @throws IllegalStateException 如果管道已被中断（breakChain 后被调用）
     */
    void next(ChatContext context);

    /**
     * 中断管道执行，跳过当前 Stage 之后的所有 Stage。
     *
     * @param context 对话上下文
     */
    void breakChain(ChatContext context);

    /**
     * 获取当前正在执行的 Stage 序号（从 0 开始）。
     *
     * @return 当前 Stage 的索引
     */
    int getCurrentStage();
}
