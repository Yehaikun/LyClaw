package lyjew.com.lyclaw.orchestration.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.orchestration.DTO.DeepSeekDTO;
import lyjew.com.lyclaw.model.ChatRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/test")
public class SSEController {

    ObjectMapper mapper = new ObjectMapper();

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendSSE(
            ) throws InterruptedException {

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
        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(jsonBody)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnSubscribe(s -> {
                    System.out.println("开始发送消息......");
                    try{
                        Thread.sleep(3000);
                    }catch (Exception e){}

                })
                .map(this::parseChunk).retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)));

    }

    ServerSentEvent<String> parseChunk(String chunk){
        System.out.println("chunk是："+chunk);
        DeepSeekDTO deepSeekDTO;
        try {
            deepSeekDTO = mapper.readValue(chunk, DeepSeekDTO.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析序列化为java对象出错："+e.getStackTrace());
        }
        String reasoning=deepSeekDTO.getChoices().get(0).getDelta().getContent();
        String content=deepSeekDTO.getChoices().get(0).getDelta().getReasoningContent();
        return ServerSentEvent.<String>builder().event("message").data(reasoning).build();
    }
}
