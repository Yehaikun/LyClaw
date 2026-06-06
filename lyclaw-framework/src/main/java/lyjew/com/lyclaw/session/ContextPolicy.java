package lyjew.com.lyclaw.session;

import java.util.List;

import lyjew.com.lyclaw.model.Message;

/**
 * 上下文裁剪策略 SPI —— 决定哪些消息送入 LLM 调用。
 *
 * <p>LLM 的上下文窗口有限，当对话历史超出窗口大小时需要裁剪。
 * 不同策略适用于不同场景：
 * <ul>
 *   <li>{@link SlidingWindowPolicy} —— 保留最近 N 条消息（简单高效）</li>
 *   <li>{@link TokenBudgetPolicy} —— 控制在 T 个 token 以内（精确）</li>
 *   <li>{@link SummaryCompressPolicy} —— 将旧消息压缩为摘要（推荐）</li>
 * </ul>
 *
 * <p>可通过 {@link CompositePolicy} 组合多个策略。</p>
 */
public interface ContextPolicy {

    /**
     * 从完整消息列表中裁剪出送入 LLM 的子集。
     *
     * @param sessionId   会话 ID（可能用于读取会话变量）
     * @param fullMessages 完整消息列表（按时间正序）
     * @param ctx         裁剪上下文（入参）
     * @return 裁剪后的消息列表（仍按时间正序）
     */
    List<Message> prune(String sessionId, List<Message> fullMessages, ContextPolicyContext ctx);

    /** 获取策略名称 */
    default String name() {
        return getClass().getSimpleName();
    }
}
