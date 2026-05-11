package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * LyClaw框架基础自动配置类。
 *
 * <p>Spring Boot 3.x风格的自动配置入口，使用{@code @AutoConfiguration}注解
 * （替代传统的{@code @Configuration} + spring.factories方式）。
 * 通过{@code @ComponentScan}扫描lyclaw.com.lyclaw.autoconfigure包下的所有组件，
 * 实现自动装配。</p>
 *
 * <p>该类的作用是在Spring Boot应用启动时自动发现并注册LyClaw框架的
 * 所有自动配置组件（配置绑定、排序工具等），无需手动编写配置导入。</p>
 *
 * <p>数据流向：Spring Boot启动 → 加载AutoConfiguration → 组件扫描 →
 * 注册所有LyClaw配置Bean → 应用就绪</p>
 *
 * @author lyjew
 */
@AutoConfiguration
@ComponentScan(basePackages = "lyjew.com.lyclaw.autoconfigure")
public class LyClawBaseAutoConfiguration {
}
