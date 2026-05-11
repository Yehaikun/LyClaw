package lyjew.com.lyclaw.plan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * LyClaw规划服务启动类。
 *
 * <p>作为Spring Boot微服务入口，负责启动规划子系统。该服务通过Nacos进行服务注册与发现，
 * 扫描 {@code lyjew.com.lyclaw} 包路径下的所有组件，提供任务规划、分解、验证等核心能力。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li>初始化Spring容器及所有规划相关Bean（DAG规划器、CoT规划器等）</li>
 *   <li>向Nacos注册中心注册为 {@code lyclaw-plan-service}</li>
 *   <li>加载共享的基础设施配置（来自 {@code lyjew.com.lyclaw} 包）</li>
 * </ul>
 *
 * @see SpringBootApplication
 * @see EnableDiscoveryClient
 */
@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class PlanServiceApplication {

    /**
     * 应用主入口，启动Spring Boot应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(PlanServiceApplication.class, args);
    }
}
