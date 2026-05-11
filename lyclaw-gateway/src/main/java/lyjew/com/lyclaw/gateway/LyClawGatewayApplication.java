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

    public static void main(String[] args) {
        SpringApplication.run(LyClawGatewayApplication.class, args);
    }
}
