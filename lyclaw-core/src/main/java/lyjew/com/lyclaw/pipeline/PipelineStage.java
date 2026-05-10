package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 管道阶段抽象 —— Pipeline 中的一个独立处理步骤。
 *
 * <p>每个 PipelineStage 是一个处理单元，通过 ChatContext 与其他 Stage 共享数据。
 * 通过 Chain 对象链接多个阶段，每个阶段可通过 chain.next() 将控制权传递给下一阶段，
 * 或通过 chain.breakChain() 中断流程。</p>
 *
 * <p><b>设计动机</b>：将对话处理流程拆分为多个独立阶段，每个阶段职责单一。
 * 新增阶段只需新建类实现此接口 + @Component，PipelineBuilder 自动发现注册。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Pipeline
 * @see Chain
 */
public interface PipelineStage {

    /**
     * 判断当前阶段是否适用于这个上下文。
     *
     * <p>返回 false 时 {@link DefaultPipeline} 自动跳过此阶段，
     * 控制权直接传递给下一个 Stage。返回 true 时执行 process()。</p>
     *
     * <p>默认实现返回 true（全部执行）。需要按条件跳过的 Stage 可重写此方法。</p>
     *
     * @param context 对话上下文
     * @return true 表示需要执行此阶段
     */
    default boolean supports(ChatContext context) {
        return true;
    }

    /**
     * 执行当前阶段的处理逻辑。
     *
     * <p>实现模式：从 ChatContext 读取前置 Stage 处理后的数据，
     * 执行本阶段逻辑，将结果写回 ChatContext。
     * 默认应调用 chain.next(context) 将控制权传递给下一阶段。</p>
     *
     * @param context 对话上下文（可读写，阶段间共享）
     * @param chain   阶段链控制器，控制向上/下游传递
     */
    void process(ChatContext context, Chain chain);

    /**
     * 获取阶段的执行顺序。值越小越先执行。
     *
     * <p>建议各阶段之间预留间隔（如 100、200、300），
     * 以便后续在已有阶段之间插入新阶段而不需要修改所有 order。</p>
     *
     * @return 执行优先级（值越小优先级越高）
     */
    int getOrder();

    /**
     * 获取阶段名称，用于日志输出、监控指标标签和调试。
     *
     * @return 阶段名称（非 null），如 "ContextBuild"、"Interceptor"、"ToolCallLoop"
     */
    String getStageName();
}
