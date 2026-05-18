package lyjew.com.lyclaw.facade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * LyClaw 门面服务启动入口。
 *
 * 统一对外 HTTP 入口，负责请求路由和权限门控。
 * 通过 Feign 调用下游微服务（action、memory、plan、reflect）。
 */
@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "lyjew.com.lyclaw.feign")
public class FacadeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FacadeApplication.class, args);
    }
}
