package lyjew.com.lyclaw.protocol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
@EnableDiscoveryClient
public class ProtocolServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProtocolServiceApplication.class, args);
    }
}
