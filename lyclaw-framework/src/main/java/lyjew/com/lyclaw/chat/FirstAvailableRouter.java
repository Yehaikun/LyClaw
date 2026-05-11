package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.model.ChatRequest;

import java.util.List;
import java.util.Map;

/**
 * 默认兜底路由策略——按注册顺序返回第一个可用模型。
 *
 * <p>当没有标注 @ModelRouter 的 Bean 时框架使用此路由。
 * 遍历所有已注册 Provider 和模型，返回找到的第一个。
 */
public class FirstAvailableRouter implements ModelRouter {

    private final ChatModelRegistry registry;

    public FirstAvailableRouter(ChatModelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public RoutingDecision route(ChatRequest request, Object context) {
        Map<String, List<ChatModel>> all = registry.getAll();
        for (Map.Entry<String, List<ChatModel>> entry : all.entrySet()) {
            List<ChatModel> models = entry.getValue();
            if (!models.isEmpty()) {
                ChatModel first = models.get(0);
                return RoutingDecision.to(entry.getKey(), first.model(),
                        RoutingTier.STANDARD, "first_available");
            }
        }
        throw new IllegalStateException("没有可用的 ChatModel。请配置至少一个 AI 模型 Provider。");
    }
}
