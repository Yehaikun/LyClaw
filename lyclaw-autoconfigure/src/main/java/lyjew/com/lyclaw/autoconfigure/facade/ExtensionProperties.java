package lyjew.com.lyclaw.autoconfigure.facade;

/**
 * 扩展注册管道的配置属性类，属性值通过 Spring Boot 的 {@code @ConfigurationProperties}
 * 机制与配置文件中 {@code lyclaw.extension} 前缀下的属性绑定。
 *
 * <p><b>配置项说明：</b></p>
 * <ul>
 *   <li><b>filteringEnabled（过滤开关）：</b>控制扩展注册过程中是否启用条件过滤。
 *       默认值为 {@code true}，表示所有通过注解发现的扩展候选者都必须经过过滤器链
 *       （如 {@link ConditionFilter}）的检查。如果设置为 {@code false}，则所有
 *       发现的扩展候选者无条件注册，适用于开发调试阶段快速启用所有组件。</li>
 *   <li><b>orderingStrategy（排序策略）：</b>控制扩展组件的排序方式。默认值为
 *       {@code "topology"}（拓扑排序），根据注解中声明的 after/before 约束关系
 *       自动推导执行顺序。另一个可选值为 {@code "numeric"}（数值排序），直接使用
 *       组件声明的 order 数值进行排序，不关注依赖关系。</li>
 *   <li><b>failFast（快速失败）：</b>控制扩展注册过程中遇到异常时的处理行为。
 *       默认值为 {@code false}，表示即使某个扩展候选者处理失败，也会继续处理
 *       剩余的候选者。设置为 {@code true} 时遇到异常立即中断整个注册流程，
 *       适用于要求所有扩展必须正确初始化的生产环境。</li>
 * </ul>
 *
 * <p>配置示例（application.yml）：</p>
 * <pre>{@code
 * lyclaw:
 *   extension:
 *     filtering-enabled: true
 *     ordering-strategy: topology
 *     fail-fast: false
 * }</pre>
 */
public class ExtensionProperties {

    private boolean filteringEnabled = true;
    private String orderingStrategy = "topology"; // topology | numeric
    private boolean failFast = false;

    public boolean isFilteringEnabled() {
        return filteringEnabled;
    }

    public void setFilteringEnabled(boolean filteringEnabled) {
        this.filteringEnabled = filteringEnabled;
    }

    public String getOrderingStrategy() {
        return orderingStrategy;
    }

    public void setOrderingStrategy(String orderingStrategy) {
        this.orderingStrategy = orderingStrategy;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }
}
