package lyjew.com.lyclaw.action;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LyClaw 动作服务的 Spring Boot 启动类。
 *
 * <p>作为微服务架构中的动作执行服务，负责工具调用和技能执行。
 * 扫描基础包 {@code lyjew.com.lyclaw} 下的所有 Spring 组件。
 * 通过 {@link EnableDiscoveryClient} 注册到服务发现中心（如 Nacos/Consul/Eureka）。</p>
 */
@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
public class ActionServiceApplication {

    /**
     * 应用程序入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ActionServiceApplication.class, args);
    }
}
