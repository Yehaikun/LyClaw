package lyjew.com.lyclaw.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class LyClawGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LyClawGatewayApplication.class, args);
    }
}
