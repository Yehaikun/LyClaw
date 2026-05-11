package lyjew.com.lyclaw.protocol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * LyClaw协议服务启动类。
 *
 * <p>作为Spring Boot微服务入口，负责启动协议子系统。该服务通过Nacos进行服务注册与发现，
 * 扫描 {@code lyjew.com.lyclaw} 包路径下的所有组件，提供MCP (Model Context Protocol)
 * 和A2A (Agent-to-Agent)协议支持。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li>启动MCP客户端，用于工具发现和调用外部MCP服务器</li>
 *   <li>启动A2A网关和Agent发现服务，支持多Agent协作</li>
 *   <li>向Nacos注册中心注册为 {@code lyclaw-protocol-service}</li>
 *   <li>加载共享的基础设施配置（来自 {@code lyjew.com.lyclaw} 包）</li>
 * </ul>
 *
 * @see SpringBootApplication
 * @see EnableDiscoveryClient
 */
@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class ProtocolServiceApplication {

    /**
     * 应用主入口，启动Spring Boot应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ProtocolServiceApplication.class, args);
    }
}
