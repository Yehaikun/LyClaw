package lyjew.com.lyclaw.autoconfigure.config;

import lyjew.com.lyclaw.autoconfigure.facade.ExtensionProperties;

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
}
