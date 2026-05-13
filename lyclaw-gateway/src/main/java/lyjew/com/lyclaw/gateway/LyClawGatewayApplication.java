package lyjew.com.lyclaw.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * LyClaw网关服务启动类。
 * <p>
 * 基于Spring Cloud Gateway的API网关，负责请求路由、链路追踪注入和CORS配置。
 * 注册到Nacos服务发现中心，通过负载均衡(lb://)将请求分发到各个微服务。
 * </p>
 */
@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class LyClawGatewayApplication {

    /**
     * Spring Boot 应用启动入口。
     *
     * <p>本方法是 LyClaw 网关微服务的标准 Spring Boot 启动引导入口。它通过
     * {@link SpringApplication#run(Class, String...)} 启动整个 Spring 应用上下文，
     * 执行以下初始化流程：</p>
     * <ol>
     *   <li><b>自动配置加载</b> — 由于类上标注了 {@code @SpringBootApplication}，
     *       且扫描基础包设置为 {@code lyjew.com.lyclaw}，Spring Boot 的自动配置机制
     *       会扫描并加载该包及其所有子包下的组件、配置类和服务。</li>
     *   <li><b>服务发现注册</b> — 由于类上标注了 {@code @EnableDiscoveryClient}，
     *       应用启动后会自动向 Nacos 服务注册中心发送心跳并注册自身实例，
     *       使得其他微服务可以通过服务名（如 {@code lyclaw-gateway}）发现并调用本网关。</li>
     *   <li><b>网关路由初始化</b> — Spring Cloud Gateway 的自动配置会根据配置文件中的
     *       路由定义（routes）加载路由规则，建立请求路径到下游微服务的映射关系。</li>
     *   <li><b>过滤器链装配</b> — 全局过滤器（如 CORS 过滤器、链路追踪过滤器、
     *       请求日志过滤器等）按优先级顺序装配到网关过滤器链中。</li>
     *   <li><b>嵌入式 Web 服务器启动</b> — 默认启动 Netty 嵌入式服务器，
     *       绑定配置的端口（通常为 8080），开始监听入站 HTTP 请求。</li>
     *   <li><b>健康检查端点就绪</b> — Actuator 健康检查端点启动，
     *       Kubernetes/Nacos 可通过该端点判断 Pod 是否就绪以接入流量。</li>
     * </ol>
     *
     * <p>与其他 LyClaw 微服务 Application 类（如 {@code LyClawChatApplication}、
     * {@code LyClawAgentApplication} 等）的 main() 方法保持一致的设计模式：
     * 都将自身 {@code .class} 字面量传递给 SpringApplication.run()，
     * 使其成为 Spring Boot 的标准启动器。</p>
     *
     * <p>当应用启动失败时（如端口冲突、Nacos 不可达、配置缺失等），
     * Spring Boot 会输出详细的错误诊断信息并终止进程。
     * 在 Kubernetes 环境下，Pod 会被自动重启以尝试恢复。</p>
     *
     * @param args 命令行参数，传递给 Spring Boot 应用。
     *             支持标准的 Spring Boot 命令行参数，如
     *             {@code --server.port=8081}（覆盖端口）、
     *             {@code --spring.profiles.active=prod}（激活生产环境配置）、
     *             {@code --debug}（启用调试日志）等。
     *             通常在生产容器环境中为空的 String 数组。
     */
    public static void main(String[] args) {
        SpringApplication.run(LyClawGatewayApplication.class, args);
    }
}
