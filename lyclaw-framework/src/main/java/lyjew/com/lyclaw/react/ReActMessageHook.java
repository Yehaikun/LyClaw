package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;

/**
 * ReAct循环消息钩子——ToolCallLoop每产生一条新消息时回调。
 *
 * 这是框架层的SPI接口，不依赖任何存储实现。实现类可以是：
 * - 持久化钩子（SessionManager）
 * - 指标采集钩子（MetricsCollector）
 * - 日志审计钩子（AuditLogger）
 * - Phase 3 压缩触发检查钩子（CompactionTrigger）
 *
 * 所有钩子按注册顺序同步调用。每个钩子实现必须快速返回（O(1)内存操作），
 * 耗时操作在钩子内部异步化。
 */
@FunctionalInterface
public interface ReActMessageHook {
    /**
     * 当ReAct循环产生一条新消息时调用。
     * 此时消息已加入 ChatRequest.messages 列表，但尚未持久化。
     *
     * @param session 当前会话（可能为null，如心跳会话或测试场景）
     * @param message 刚产生的消息（role可能是user/assistant/tool）
     */
    void onMessage(Session session, Message message);
}
