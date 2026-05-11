package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 旧版流水线链式控制接口，已废弃。
 *
 * 该接口定义了流水线中阶段之间的链式控制逻辑，包括推进到下一阶段、
 * 中断执行链以及获取当前阶段索引。在旧版同步流水线中使用，
 * 新版请使用 {@link ReactivePipeline} 配合 {@link PipelineContext} 来实现。
 *
 * @deprecated 请使用 {@link ReactivePipeline} 替代
 */
@Deprecated
public interface Chain {

    /**
     * 推进到流水线的下一个阶段。
     *
     * @param context 聊天上下文，包含请求、会话、消息等信息
     */
    void next(ChatContext context);

    /**
     * 中断当前流水线的执行链，后续阶段不再执行。
     *
     * @param context 聊天上下文
     */
    void breakChain(ChatContext context);

    /**
     * 获取当前正在执行的阶段索引。
     *
     * @return 当前阶段的序号（从 0 开始）
     */
    int getCurrentStage();
}
