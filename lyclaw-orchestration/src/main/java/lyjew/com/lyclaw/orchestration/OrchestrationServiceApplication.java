package lyjew.com.lyclaw.orchestration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * LyClaw 编排服务启动入口。
 *
 * 该服务负责协调多个 AI Agent 之间的任务分配、通信和协作流程。
 * 启用了服务发现（Nacos/Consul）以便在微服务集群中自动注册，
 * 并通过 Feign 声明式客户端调用 action、memory、plan、reflect 等下游微服务。
 */
@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient  // 启用服务注册与发现，注册到 Nacos/Consul 注册中心
@EnableFeignClients(basePackages = "lyjew.com.lyclaw.feign")  // 扫描 feign 接口包，自动生成代理 Bean
public class OrchestrationServiceApplication {

    /**
     * Spring Boot 应用主入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(OrchestrationServiceApplication.class, args);
    }
}
