package lyjew.com.lyclaw.action;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class ActionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ActionServiceApplication.class, args);
    }
}
