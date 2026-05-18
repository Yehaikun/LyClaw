package lyjew.com.lyclaw.react;

import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;

public class SSETest9 {
    public static void main(String[] args) {
        ServerSentEvent<String> sse1 = ServerSentEvent.<String>builder()
                .event("message")
                .data("Hello World")
                .id("msg-001")
                .retry(Duration.ofSeconds(2))
                .comment("注释")
                .build();
        System.out.println(Runtime.getRuntime().availableProcessors());
    }
}
