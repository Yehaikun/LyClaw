package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.chat.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暴露已注册模型适配器列表和 provider 信息。
 */
@Endpoint(id = "lyclaw-adapters")
public class LyClawAdaptersEndpoint {

    private final ChatFacade chatFacade;

    /**
     * 构造适配器端点，注入 ChatFacade 依赖（可选）。
     *
     * <p>ChatFacade 是聊天模型调用的统一门面，通过其 {@code getModels()} 方法可以
     * 获取所有已注册的模型适配器列表。使用 {@code @Autowired(required = false)}
     * 注解确保即使在没有注册任何聊天模型的环境中（如纯工具使用的场景），端点也能
     * 正常初始化并返回可用性状态，而非导致应用启动失败。注入的 ChatFacade 为 null
     * 时，端点将返回 {@code "available": false}。</p>
     *
     * @param chatFacade 聊天门面实例（可为 null），由 Spring 容器在可用时自动注入
     */
    @Autowired
    public LyClawAdaptersEndpoint(@Autowired(required = false) ChatFacade chatFacade) {
        this.chatFacade = chatFacade;
    }

    /**
     * Actuator 只读操作，返回当前已注册的所有聊天模型适配器列表及其元数据信息。
     *
     * <p>该方法是 {@code /actuator/lyclaw-adapters} 端点的核心实现，通过
     * {@link ChatFacade#getModels()} 获取按 Provider 分组的模型列表，然后扁平化
     * 处理为统一的适配器列表。每个适配器条目包含 provider 名称、model 标识、
     * capabilities 能力字符串和实现类全限定名四个字段。</p>
     *
     * <p><b>可用性检测：</b>如果 ChatFacade 为 null（未注入），返回包含
     * {@code "available": false} 和原因说明的 Map，表示当前环境中没有聊天模型注册。</p>
     *
     * <p><b>统计信息：</b>除了适配器列表外，还返回 availableProviders（所有 Provider 名称）
     * 和 adapterCount（适配器总数）两个汇总字段，方便运维人员快速了解模型注册概况。</p>
     *
     * @return 包含适配器列表和统计信息的 Map，如果无可用 ChatFacade 则返回不可用状态
     */
    @ReadOperation
    public Map<String, Object> adapters() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (chatFacade == null) {
            result.put("available", false);
            result.put("reason", "ChatFacade bean not available");
            return result;
        }

        Map<String, List<ChatModel>> models = chatFacade.getModels();
        result.put("availableProviders", models.keySet());
        int total = models.values().stream().mapToInt(List::size).sum();
        result.put("adapterCount", total);
        result.put("adapters", models.entrySet().stream()
                .flatMap(e -> e.getValue().stream()
                    .map(m -> {
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("provider", m.provider());
                        a.put("model", m.model());
                        a.put("capabilities", m.capabilities() != null ? m.capabilities().toString() : "unknown");
                        a.put("class", m.getClass().getName());
                        return a;
                    }))
                .toList());

        return result;
    }
}
