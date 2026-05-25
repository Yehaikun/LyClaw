package lyjew.com.lyclaw.autoconfigure.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lyjew.com.lyclaw.autoconfigure.facade.ExtensionProperties;
import lyjew.com.lyclaw.config.AgentDeclaration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * LyClaw框架配置属性类。
 *
 * <p>Spring Boot配置属性映射类，通过{@code @ConfigurationProperties(prefix = "lyclaw")}
 * 将application.yml中以"lyclaw"为前缀的配置自动绑定到该类的字段中。</p>
 *
 * <p>当前包含的配置项：
 * <ul>
 *   <li>extension：扩展属性配置，通过{@link ExtensionProperties}管理扩展模块的开关和参数</li>
 *   <li>agents：声明式 Agent 定义，key 为 agent 名称，value 为 {@link AgentDeclaration}</li>
 * </ul>
 * </p>
 *
 * <p>使用{@code @NestedConfigurationProperty}注解标记嵌套属性，
 * 使得Spring Boot能够递归地绑定嵌套对象的配置。</p>
 *
 * @author lyjew
 */
@ConfigurationProperties(prefix = "lyclaw")
public class LyClawConfigurationProperties {

    /** 扩展属性配置，管理扩展模块的开关和参数,找 lyclaw.extension. 下面的配置项装配到这里 */
    @NestedConfigurationProperty
    private ExtensionProperties extension = new ExtensionProperties();

    /** 声明式 Agent 定义，对应 {@code lyclaw.agents.<agentName>.<property>} */
    @NestedConfigurationProperty
    private Map<String, AgentDeclaration> agents = new LinkedHashMap<>();

    /** @Agent 接口扫描配置 */
    @NestedConfigurationProperty
    private ScanProperties scan = new ScanProperties();

    /**
     * 获取扩展模块配置属性。
     *
     * @return 扩展属性对象
     */
    public ExtensionProperties getExtension() {
        return extension;
    }

    /**
     * 设置扩展模块配置属性。
     *
     * @param extension 扩展属性对象
     */
    public void setExtension(ExtensionProperties extension) {
        this.extension = extension;
    }

    /**
     * 获取声明式 Agent 定义。
     *
     * @return agent 名称到声明的映射
     */
    public Map<String, AgentDeclaration> getAgents() {
        return agents;
    }

    /**
     * 设置声明式 Agent 定义。
     *
     * @param agents agent 名称到声明的映射
     */
    public void setAgents(Map<String, AgentDeclaration> agents) {
        this.agents = agents;
    }

    /**
     * 获取 @Agent 接口扫描配置。
     *
     * @return 扫描配置
     */
    public ScanProperties getScan() {
        return scan;
    }

    /**
     * 设置 @Agent 接口扫描配置。
     *
     * @param scan 扫描配置
     */
    public void setScan(ScanProperties scan) {
        this.scan = scan;
    }

    /**
     * @Agent 接口扫描配置。
     *
     * <p>指定要扫描 {@code @Agent} 注解接口的基础包路径。
     * 框架默认只扫描 {@code lyjew.com.lyclaw}，如果使用方
     * 在其它包中定义了 {@code @Agent} 接口（如
     * {@code com.yizhaoqi.smartpai.agent}），需要通过此配置添加。
     */
    public static class ScanProperties {
        private List<String> basePackages = List.of("lyjew.com.lyclaw");

        public List<String> getBasePackages() {
            return basePackages;
        }

        public void setBasePackages(List<String> basePackages) {
            this.basePackages = basePackages;
        }
    }
}
