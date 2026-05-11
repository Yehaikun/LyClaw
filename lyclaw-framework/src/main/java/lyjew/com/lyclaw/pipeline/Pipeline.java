package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 旧版同步流水线接口，已废弃。
 *
 * 该接口定义了流水线的基本执行模式：接收一个聊天上下文，按顺序执行所有注册的阶段。
 * 每个阶段通过 {@link PipelineStage} 接口实现处理逻辑。
 * 新版请使用响应式流水线 {@link ReactivePipeline} 来支持 SSE 事件流。
 *
 * @deprecated 请使用 {@link ReactivePipeline} 替代
 */
@Deprecated
public interface Pipeline {

    /**
     * 执行流水线的所有阶段。
     *
     * @param context 聊天上下文，在阶段间传递和共享
     */
    void execute(ChatContext context);

    /**
     * 获取流水线中所有已注册的阶段列表。
     *
     * @return 按注册顺序排列的阶段列表
     */
    List<PipelineStage> getStages();
}
