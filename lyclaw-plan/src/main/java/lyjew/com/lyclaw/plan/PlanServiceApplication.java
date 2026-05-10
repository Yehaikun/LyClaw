package lyjew.com.lyclaw.plan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class PlanServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlanServiceApplication.class, args);
    }
}
