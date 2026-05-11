package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;

import java.util.List;
import java.util.Map;

/**
 * 模型适配层统一门面，将 ChatModel 注册表、路由、ChatClient 统一暴露。
 *
 * <p>这是框架使用者操作 AI 模型的唯一入口，业务代码通过依赖注入获取此门面实例。
 * 内部整合路由决策、模型查找、调用执行，业务代码无需关心底层实现。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 通过依赖注入获取
 * {@literal @}Autowired ChatFacade chat;
 * RoutingDecision decision = chat.route(request, context);
 * ModelResponse response = chat.chat(request);
 * }</pre>
 */
public interface ChatFacade {

    // ── 模型管理 ──

    /** 根据路由决策获取 ChatModel */
    ChatModel resolveModel(RoutingDecision decision);

    /** 获取所有已注册的模型 */
    Map<String, List<ChatModel>> getModels();

    /** 获取指定 Provider 的可用模型列表 */
    List<String> getAvailableModels(String provider);

    // ── 对话 ──

    /** 创建流式对话 Builder */
    ChatClient chat();

    /** 同步单次对话 */
    ModelResponse chat(ChatRequest request);

    // ── 路由 ──

    /** 为请求选择最优模型 */
    RoutingDecision route(ChatRequest request, Object context);

    /** 运行时切换路由策略 */
    void switchRouter(String routerName);

    // ── Token 计数 ──

    /** 计算指定 Provider/模型的 Token 数 */
    int countTokens(String provider, String model, String text);

    // ── 健康检查 ──

    /** 对所有已注册模型执行健康检查 */
    Map<String, Boolean> healthCheck();
}
