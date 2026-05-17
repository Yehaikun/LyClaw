package lyjew.com.lyclaw.react;

import reactor.core.publisher.Flux;

import java.time.Duration;

public class SSETest5 {
    public static void main(String[] args) throws InterruptedException {
// 热发布者示例：使用 publish() + autoConnect()
        Flux<Long> hot = Flux.interval(Duration.ofMillis(100))  // 每100ms发射一个递增数字
                .publish()    // 转为 ConnectableFlux（可连接的热发布者）
                .autoConnect();  // 第一个订阅者到达时自动开始

// 第1个订阅者立即开始接收数据
        hot.subscribe(n -> System.out.println("A: " + n));

// 500ms 后，第2个订阅者才加入
        Thread.sleep(500);
        hot.subscribe(n -> System.out.println("  B: " + n));
// 输出（大致）:
// A: 0
// A: 1
// A: 2
// A: 3
// A: 4
//   B: 5  ← B 从 5 开始，错过了 0-4
// A: 5
//   B: 6
// A: 6



    }
}
