package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LyClaw 框架健康指示器，报告模型和工具注册表状态。
 */
@Component
public class LyClawHealthIndicator implements HealthIndicator {

    @Autowired(required = false)
    private ChatFacade chatFacade;

    @Autowired(required = false)
    private ToolRegistry toolRegistry;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // ChatFacade 健康检查
        if (chatFacade != null) {
            Map<String, Boolean> healthResults = chatFacade.healthCheck();
            int modelCount = healthResults.size();
            boolean anyAvailable = healthResults.values().stream().anyMatch(Boolean::booleanValue);
            builder.withDetail("adapters", modelCount)
                   .withDetail("adapterConfigured", anyAvailable)
                   .withDetail("adapterHealth", healthResults);
            if (!anyAvailable && modelCount > 0) {
                builder.withDetail("adapterWarning", "Models registered but none healthy");
            }
        } else {
            builder.withDetail("adapters", "unavailable");
        }

        // Tool registry health
        if (toolRegistry != null) {
            int toolCount = toolRegistry.getAllDefinitions().size();
            builder.withDetail("tools", toolCount);
            if (toolCount == 0) {
                builder.withDetail("toolWarning", "No tools registered");
            }
        } else {
            builder.withDetail("tools", "unavailable");
        }

        return builder.build();
    }
}
