package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 管道编排入口 —— 将对话处理分解为多个可编排的阶段（PipelineStage）。
 *
 * <p>Pipeline 是 DefaultEngine 内部的核心编排组件。DefaultEngine.execute()
 * 内部通过 PipelineBuilder 构建 Pipeline，然后调用 execute() 执行整个流程。</p>
 *
 * <p><b>设计动机</b>：不使用一个巨大的方法来实现对话处理流程，
 * 而是将流程拆分为 ContextBuild → Interceptor → ToolCallLoop → Metrics → ResponseBuild
 * 五个阶段。每个阶段独立实现 PipelineStage 接口，通过 PipelineBuilder 链式组装。
 * 新增阶段只需新建 PipelineStage 实现类 + addStage() 即可。</p>
 *
 * <p><b>为什么 Pipeline.execute() 不返回 ChatResult</b>：
 * Pipeline 只是编排阶段流程的容器，不直接处理返回值。
 * ChatResult 的构建由最后一个阶段（ResponseBuildStage）负责，
 * 结果存储在 ChatContext 中，后续由 Engine 消费。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see PipelineStage
 * @see PipelineBuilder
 */
public interface Pipeline {

    /**
     * 执行整个管道。每个 PipelineStage 按顺序依次执行，
     * 通过 {@link Chain} 控制阶段间的流转。
     *
     * <p>执行过程中出现异常时，由 ErrorPolicy 决定是重试还是终止。
     * 结果存放在 {@link ChatContext#getResult()} 中。</p>
     *
     * @param context 包含请求、会话、记忆、拦截器链的完整上下文
     */
    void execute(ChatContext context);

    /**
     * 获取当前管道的所有阶段列表，用于日志、监控和调试。
     *
     * @return 按执行顺序排列的阶段列表（不可变视图）
     */
    List<PipelineStage> getStages();
}