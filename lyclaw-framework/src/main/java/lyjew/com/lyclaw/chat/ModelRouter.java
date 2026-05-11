package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.model.ChatRequest;

/**
 * 模型路由策略接口，根据请求内容和上下文决定使用哪个模型。
 *
 * <p>标注了 @ModelRouter 的类自动注册到框架的路由注册表。
 * 框架优先使用 defaultRouter=true 的策略，无时回退到 FirstAvailableRouter。
 *
 * <p>典型实现：
 * <ul>
 *   <li>RegexKeywordRouter——基于正则关键词本地匹配，&lt;1ms 延迟</li>
 *   <li>LlmBasedRouter——将路由决策委托给 LLM，高准确率但额外开销</li>
 * </ul>
 */
public interface ModelRouter {

    /**
     * 根据请求和上下文做出路由决策。
     *
     * @param request 聊天请求
     * @param context 聊天上下文（可能为 null）
     * @return 路由决策，包含目标 Provider、模型、层级和决策原因
     */
    RoutingDecision route(ChatRequest request, Object context);
}
