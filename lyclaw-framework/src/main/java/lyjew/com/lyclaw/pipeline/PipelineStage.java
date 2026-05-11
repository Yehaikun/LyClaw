package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 旧版流水线阶段接口，已废弃。
 *
 * 定义流水线中单个阶段的行为：判断是否支持当前上下文、执行处理逻辑、
 * 返回执行顺序和阶段名称。在旧版同步流水线中使用，
 * 新版请使用 {@link ReactivePipelineStage} 来实现基于响应式流的阶段处理。
 *
 * @deprecated 请使用 {@link ReactivePipelineStage} 替代
 */
@Deprecated
public interface PipelineStage {

    /**
     * 判断当前阶段是否支持处理该上下文。
     * 默认返回 true，子类可覆盖以实现条件性跳过。
     *
     * @param context 聊天上下文
     * @return true 表示支持处理，false 表示跳过此阶段
     */
    default boolean supports(ChatContext context) {
        return true;
    }

    /**
     * 执行当前阶段的处理逻辑。
     *
     * @param context 聊天上下文
     * @param chain   链式控制器，用于推进到下一阶段或中断执行
     */
    void process(ChatContext context, Chain chain);

    /**
     * 获取阶段的执行顺序号。
     *
     * @return 序号，数值越小越先执行
     */
    int getOrder();

    /**
     * 获取阶段的可读名称，用于日志和诊断。
     *
     * @return 阶段名称
     */
    String getStageName();
}
