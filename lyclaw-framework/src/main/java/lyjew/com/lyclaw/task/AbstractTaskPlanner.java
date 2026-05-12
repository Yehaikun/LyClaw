package lyjew.com.lyclaw.task;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.model.Message;

import java.util.List;
import java.util.UUID;

/**
 * 任务规划器抽象基类，提供 extractIntent 等公共方法，减少子类样板代码。
 *
 * <p>子类只需实现 {@link #plan(ChatContext, String)} 和 {@link #decompose(TaskNode, DecompositionStrategy)}，
 * 基类自动处理意图提取和 plan/optimize 的默认行为。
 */
public abstract class AbstractTaskPlanner implements TaskPlanner {

    @Override
    public TaskPlan plan(ChatContext context) {
        String intent = extractIntent(context);
        return plan(context, intent);
    }

    @Override
    public TaskPlan optimize(AgentResult previousResult) {
        String summary = previousResult != null && previousResult.getSummary() != null
                ? previousResult.getSummary() : "";
        List<TaskNode> nodes = List.of(new TaskNode(
                "optimized-" + UUID.randomUUID().toString().substring(0, 8),
                "OPTIMIZE", "Optimized based on previous result: " + summary,
                List.of(), List.of(), 30_000L));
        return new SimpleTaskPlan(nodes);
    }

    /**
     * 从聊天上下文中提取用户的核心意图。
     *
     * <p>默认取最后一条 user 消息内容，子类可重写以实现更复杂的意图识别。
     *
     * @param context 聊天上下文
     * @return 用户意图文本
     */
    protected String extractIntent(ChatContext context) {
        if (context == null || context.getRequest() == null) return "";
        List<Message> messages = context.getRequest().getMessages();
        if (messages == null || messages.isEmpty()) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if ("user".equalsIgnoreCase(msg.getRole())) {
                return msg.getContent() != null ? msg.getContent() : "";
            }
        }
        return "";
    }
}
