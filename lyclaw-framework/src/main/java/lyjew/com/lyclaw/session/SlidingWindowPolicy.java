package lyjew.com.lyclaw.session;

import java.util.ArrayList;
import java.util.List;

import lyjew.com.lyclaw.model.Message;

/**
 * 滑动窗口上下文策略 —— 保留最近 N 条消息。
 *
 * <p>简单高效的默认裁剪方式。当消息总数超过窗口大小时，
 * 丢弃最早的消息；系统提示词（首条 system 消息）始终保留。</p>
 *
 * <p>默认窗口大小：{@value DEFAULT_WINDOW_SIZE} 条消息。</p>
 */
public class SlidingWindowPolicy implements ContextPolicy {

    static final int DEFAULT_WINDOW_SIZE = 50;

    private final int windowSize;

    public SlidingWindowPolicy() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public SlidingWindowPolicy(int windowSize) {
        this.windowSize = Math.max(10, windowSize);
    }

    @Override
    public List<Message> prune(String sessionId, List<Message> fullMessages, ContextPolicyContext ctx) {
        if (fullMessages == null || fullMessages.isEmpty()) {
            return List.of();
        }
        if (fullMessages.size() <= windowSize) {
            return fullMessages;
        }

        // 保留首条 system 消息（如果有）
        Message systemMsg = null;
        if ("system".equals(fullMessages.get(0).getRole())) {
            systemMsg = fullMessages.get(0);
        }

        // 取最近 N 条（从末尾取）
        int from = fullMessages.size() - windowSize;
        List<Message> result = new ArrayList<>(fullMessages.subList(from, fullMessages.size()));

        // system 消息始终在最前面
        if (systemMsg != null) {
            result.add(0, systemMsg);
        }

        return result;
    }

    public int getWindowSize() { return windowSize; }

    @Override
    public String name() {
        return "SlidingWindow(" + windowSize + ")";
    }
}
