package lyjew.com.lyclaw.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LyClaw Web 服务启动入口。
 *
 * 统一对外 HTTP 入口，单体部署单元。
 */
@SpringBootApplication(scanBasePackages = "lyjew.com.lyclaw")
public class LyClawWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(LyClawWebApplication.class, args);
    }
}
