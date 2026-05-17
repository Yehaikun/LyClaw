package lyjew.com.lyclaw.react;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;


@SpringBootTest
public class SSETest8 {

    public static void main(String[] args) throws InterruptedException {
        //6.5
        /**
         * 1.
         * A
         * B data
         * C DATA
         * D DATA
         *
         * 2.
         * A fallback
         * B 恢复数据-boom
         */
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.deepseek.com")
                .defaultHeaders((headers)->{
                    headers.setBearerAuth("sk-b1da578246114c2383616f49b5651f1d");
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                })
                .build();
        String jsonBody = String.format("""
{
        "model": "deepseek-v4-pro",
        "messages": [
          {"role": "system", "content": "You are a helpful assistant."},
          {"role": "user", "content": "%s"}
        ],
        "thinking": {"type": "enabled"},
        "reasoning_effort": "high",
        "stream": true
      }
            """, "请给我介绍WebSocket整个握手和数据传输流程");

        webClient.post()
                .uri("/chat/completions")
                .bodyValue(jsonBody)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnSubscribe(s->{
                    System.out.println("开始发送消息......");
                })
                .doOnNext(System.out::println)
                .subscribe();
        Thread.sleep(1000000);
    }
}
