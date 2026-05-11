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

    @Autowired
    public LyClawAdaptersEndpoint(@Autowired(required = false) ChatFacade chatFacade) {
        this.chatFacade = chatFacade;
    }

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
