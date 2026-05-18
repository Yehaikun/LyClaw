package lyjew.com.lyclaw.chat;

import java.util.Collections;
import java.util.List;

import lyjew.com.lyclaw.config.AgentConfig;

/**
 * 聊天模型提供者 SPI——根据 Agent 配置和请求上下文解析具体的 ChatModel 实例。
 *
 * <p>默认实现 {@code RoutingChatModelProvider} 基于配置驱动的路由表选择模型。
 * 用户可替换为支持 A/B 测试、负载均衡、预算感知路由等的高级提供者。
 */
public interface ChatModelProvider {
    /**
     * 解析适合当前请求的 ChatModel 实例。
     *
     * @param config Agent 配置（含 model/provider 和扩展属性）
     * @return ChatModel 实例
     * @throws lyjew.com.lyclaw.exception.ModelException 当无法找到合适的模型时
     */
    ChatModel resolve(AgentConfig config);

    /** 返回所有可用模型名称列表 */
    List<String> supportedModels();

    /** 检查是否支持指定模型 */
    default boolean supports(String modelName) {
        return supportedModels().contains(modelName);
    }
}
