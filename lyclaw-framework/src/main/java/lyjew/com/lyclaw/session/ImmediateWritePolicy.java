package lyjew.com.lyclaw.session;

/**
 * 即时写策略 —— 每条消息 append 后立即 flush。
 *
 * <p>行为与旧版代码完全一致：每次写入操作立即持久化。
 * 这是默认策略，对 demo 和小规模使用友好。</p>
 */
public class ImmediateWritePolicy implements SessionWritePolicy {

    @Override
    public boolean shouldFlush(SessionWriteState state) {
        return state != null && state.getPendingCount() > 0;
    }
}
