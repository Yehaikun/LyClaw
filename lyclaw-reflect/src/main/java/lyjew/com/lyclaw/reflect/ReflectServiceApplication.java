package lyjew.com.lyclaw.reflect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class ReflectServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReflectServiceApplication.class, args);
    }
}
