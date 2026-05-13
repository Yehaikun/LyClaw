package lyjew.com.lyclaw.orchestration.flux;

import reactor.core.publisher.Mono;

public class FluxTest {
    public static void main(String[] args) {
        // === 案例 1：创建并订阅 ===
        Mono<String> greeting = Mono.just("Hello, WebFlux!");
        greeting.subscribe(
                data  -> System.out.println("收到: " + data),   // onNext
                error -> System.err.println("错误: " + error),  // onError
                ()    -> System.out.println("完成!")             // onComplete
        );

// === 案例 2：空 Mono ===
        Mono<String> empty = Mono.empty();
        String result = empty
                .defaultIfEmpty("默认值")  // 空时给默认值
                .block();                   // 阻塞等待
        System.out.println(result);    // 输出: 默认值

// === 案例 3：错误处理 ===
        Mono<String> errorMono = Mono.error(new RuntimeException("模拟错误"));
        String recovered = errorMono
                .onErrorReturn("已恢复")    // 出错返回默认
                .block();
        System.out.println(recovered); // 输出: 已恢复
    }
}
