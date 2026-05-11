package lyjew.com.lyclaw.chat;

import java.util.List;
import java.util.Map;

/**
 * 模型注册表，管理所有已注册的 ChatModel 实例。
 *
 * <p>ChatModelPostProcessor 扫描 @ChatModel 注解的 Bean 后调用 register() 注册，
 * 运行时通过 resolve() 查找。支持按 provider+model 精确查找和按 RoutingDecision 查找。
 */
public interface ChatModelRegistry {

    /** 注册一个模型 */
    void register(String provider, String modelName, ChatModel chatModel, ChatModelMetadata metadata);

    /** 精确查找：provider + model */
    ChatModel resolve(String provider, String modelName);

    /** 按路由决策查找 */
    ChatModel resolve(RoutingDecision decision);

    /** 列出某 Provider 的所有模型 */
    List<ChatModel> listByProvider(String provider);

    /** 获取所有已注册的模型 */
    Map<String, List<ChatModel>> getAll();

    /** 检查是否有指定 Provider 的指定模型 */
    boolean hasModel(String provider, String modelName);

    /** 获取模型元数据 */
    ChatModelMetadata getMetadata(String provider, String modelName);

    /** 获取某 Provider 的所有模型名称 */
    List<String> getModelNames(String provider);
}
