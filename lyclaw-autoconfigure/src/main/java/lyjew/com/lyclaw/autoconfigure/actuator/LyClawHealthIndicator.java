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

    /**
     * 执行 LyClaw 框架的健康检查，返回包含模型适配器和工具注册表状态的 Health 对象。
     *
     * <p>该方法是 Spring Boot Actuator 健康检查机制的标准实现，框架的总体健康状态
     * 设为 {@code Health.up()}（始终为 UP），但通过 details 中的详细字段提供各组件的
     * 真实健康信息，供运维系统（如 Kubernetes 的 liveness/readiness 探针、Prometheus
     * 监控等）进行精细化判断。</p>
     *
     * <p><b>检查维度：</b></p>
     * <ul>
     *   <li><b>ChatFacade 健康检查：</b>如果 ChatFacade 已注入，调用其
     *       {@code healthCheck()} 方法获取所有 Provider 的健康状态映射。
     *       报告字段包括 adapters（模型总数）、adapterConfigured（是否有至少一个
     *       可用模型）、adapterHealth（各 Provider 的健康详情）。如果所有模型都
     *       不可用（none healthy），添加 adapterWarning 警告信息。</li>
     *   <li><b>ToolRegistry 健康检查：</b>如果 ToolRegistry 已注入，报告已注册工具
     *       的总数。如果工具数为零，添加 toolWarning 警告信息，提示没有工具注册。</li>
     * </ul>
     *
     * <p><b>优雅降级：</b>ChatFacade 和 ToolRegistry 都使用 {@code @Autowired(required = false)}
     * 注入，如果某个组件未注册（如在最小化启动或单元测试场景中），对应维度报告
     * {@code "unavailable"} 状态，不影响整体健康检查的执行。</p>
     *
     * @return Health 对象，整体状态为 UP，details 中包含各组件的详细健康信息
     */
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
