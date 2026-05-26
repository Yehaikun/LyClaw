package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.model.Message;

/**
 * 消息生命周期钩子，提供消息收发过程的扩展点。
 */
public interface MessageLifecycleHook {

    /** 消息接收时调用。 */
    default void messageReceived(Message msg, AgentContext ctx) {}

    /** 消息发送前调用。 */
    default void messageSending(String msg, AgentContext ctx) {}

    /** 消息发送后调用。 */
    default void messageSent(String msg, AgentContext ctx) {}
}
