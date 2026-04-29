package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 管道阶段抽象 —— Pipeline 中的一个独立处理步骤。
 *
 * <p>每个 PipelineStage 是一个处理单元，负责对话流程中一个明确定义的步骤。
 * 通过 Chain 对象链接多个阶段，每个阶段处理完后必须调用 {@link Chain#next(ChatContext)}
 * 将控制权传递给下一阶段。</p>
 *
 * <p><b>设计动机</b>：将对话处理流程拆分为多个独立阶段，
 * 每个阶段职责单一（单一职责原则），通过责任链模式串联。
 * 新增阶段只需新建类实现此接口，通过 PipelineBuilder.addStage() 加入管道。</p>
 *
 * <p><b>实现约束</b>：
 * <ul>
 *   <li>process() 内部必须调用 chain.next(context) 或 chain.breakChain(context)</li>
 *   <li>不调用 chain.next() 会导致管道卡住</li>
 *   <li>order 值越小越先执行，建议阶段间预留步长（如 100、200、300）以便后续插入</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Chain
 * @see Pipeline
 */
public interface PipelineStage {

    /**
     * 执行当前阶段的处理逻辑。
     *
     * <p>典型实现模式：
     * <ol>
     *   <li>执行本阶段的业务逻辑</li>
     *   <li>调用 chain.next(context) 传递到下一阶段</li>
     * </ol>
     * </p>
     *
     * @param context 对话上下文（可读写，阶段间共享）
     * @param chain   阶段链控制器，用于传递到下一阶段或中断
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