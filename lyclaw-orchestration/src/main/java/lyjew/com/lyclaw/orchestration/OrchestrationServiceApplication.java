package lyjew.com.lyclaw.orchestration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LyClaw 编排服务启动入口。
 *
 * 该服务负责协调多个 AI Agent 之间的任务分配、通信和协作流程。
 * 启用了服务发现（Nacos/Consul）以便在微服务集群中自动注册。
 * Feign 客户端由 facade 模块统一管理，编排模块独立部署时需自行启用。
 */
@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
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
