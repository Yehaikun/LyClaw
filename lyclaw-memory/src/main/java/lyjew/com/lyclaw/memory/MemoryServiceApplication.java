package lyjew.com.lyclaw.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class MemoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryServiceApplication.class, args);
    }
}
