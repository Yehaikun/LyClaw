package lyjew.com.lyclaw.reflect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LyClaw反思服务启动类。
 *
 * <p>作为Spring Boot微服务入口，负责启动反思子系统。该服务通过Nacos进行服务注册与发现，
 * 扫描 {@code lyjew.com.lyclaw} 包路径下的所有组件，提供输出质量评估、错误检测、
 * 策略调整等反思能力。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li>初始化Spring容器及所有反思相关Bean（质量评估器、错误检测器、策略调整器等）</li>
 *   <li>向Nacos注册中心注册为 {@code lyclaw-reflect-service}</li>
 *   <li>加载共享的基础设施配置（来自 {@code lyjew.com.lyclaw} 包）</li>
 * </ul>
 *
 * @see SpringBootApplication
 * @see EnableDiscoveryClient
 */
@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
public class ReflectServiceApplication {

    /**
     * 应用主入口，启动Spring Boot应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ReflectServiceApplication.class, args);
    }
}
