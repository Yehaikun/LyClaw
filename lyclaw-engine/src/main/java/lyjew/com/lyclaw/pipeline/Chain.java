package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 阶段链控制器 —— 控制 PipelineStage 之间的流转。
 *
 * <p>Pipeline 内部维护了一个 PipelineStage 列表和一个指向当前阶段的索引。
 * Chain 封装了索引递增、中断标记和当前阶段查询的逻辑。</p>
 *
 * <p><b>设计动机</b>：如果没有 Chain，每个 PipelineStage 都需要知道下一个 Stage 是谁，
 * 这就形成了强耦合。Chain 作为中间层解耦了相邻 Stage：每个 Stage 只调用
 * chain.next()，由 Chain 内部决定下一个 Stage 是谁。</p>
 *
 * <p><b>使用示例</b>（典型 Stage 实现）：
 * <pre>{@code
 * public void process(ChatContext ctx, Chain chain) {
 *     // 执行本阶段逻辑
 *     log.info("Stage {} 开始", getStageName());
 *     // 传给下一阶段
 *     chain.next(ctx);
 * }
 * }</pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see PipelineStage
 */
public interface Chain {

    /**
     * 将控制权传递给链中的下一个 PipelineStage。
     * 如果当前已经是最后一个 Stage，调用此方法将结束管道执行。
     *
     * @param context 对话上下文
     * @throws IllegalStateException 如果管道已被中断（breakChain 后被调用）
     */
    void next(ChatContext context);

    /**
     * 中断管道执行，跳过当前 Stage 之后的所有 Stage。
     * 通常在某个前置 Stage 检测到不可继续的条件时调用
     * （如限流拦截器拒绝请求、上下文构建失败）。
     *
     * <p>调用此方法后，再调用 {@link #next(ChatContext)} 会抛出 IllegalStateException。</p>
     *
     * @param context 对话上下文
     */
    void breakChain(ChatContext context);

    /**
     * 获取当前正在执行的 Stage 序号（从 0 开始）。
     * 用于日志输出、监控指标和调试追踪。
     *
     * @return 当前 Stage 的索引
     */
    int getCurrentStage();
}