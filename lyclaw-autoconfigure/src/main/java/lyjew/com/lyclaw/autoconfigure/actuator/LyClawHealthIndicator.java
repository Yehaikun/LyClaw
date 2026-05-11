package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.autoconfigure.processor.AdapterAnnotationProcessor;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports LyClaw framework health: adapter reachable, tool registry non-empty.
 */
@Component
public class LyClawHealthIndicator implements HealthIndicator {

    @Autowired(required = false)
    private AdapterAnnotationProcessor adapterProcessor;

    @Autowired(required = false)
    private ToolRegistry toolRegistry;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // Adapter health
        if (adapterProcessor != null) {
            int adapterCount = adapterProcessor.getAllAdapters().size();
            boolean anyConfigured = adapterProcessor.getAllAdapters().stream()
                    .anyMatch(ModelAdapter::isConfigured);
            builder.withDetail("adapters", adapterCount)
                   .withDetail("adapterConfigured", anyConfigured);
            if (!anyConfigured && adapterCount > 0) {
                builder.withDetail("adapterWarning", "Adapters present but none configured");
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
