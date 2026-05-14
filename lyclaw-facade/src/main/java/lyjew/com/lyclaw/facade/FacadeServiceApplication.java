package lyjew.com.lyclaw.facade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class FacadeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FacadeServiceApplication.class, args);
    }
}
