package lyjew.com.lyclaw.react;

import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;

public class SSETest6 {
    public static void main(String[] args) throws InterruptedException {
        Mono<Integer> integerMono = Mono.fromCallable(() -> {
            if (Math.random() > 0.5) {
                throw new RuntimeException("出错啦！");
            }
            return 625;
        });
        integerMono.subscribe(
                System.out::println,
                (e)-> System.out.println("出错了，错误信息："+e.getStackTrace()),
                ()-> System.out.println("消费完成！")
        );

        Mono<String> defer = Mono.defer(()->Mono.just("时间是: "+System.currentTimeMillis()));
        defer.subscribe(System.out::println,
                (e)-> {System.out.println("出错啦"+e.getStackTrace());},
                ()-> System.out.println("已完成")
                );
        Thread.sleep(3000);
        defer.subscribe(System.out::println,
                (e)-> {System.out.println("出错啦"+e.getStackTrace());},
                ()-> System.out.println("已完成")
        );

        System.out.println(Mono.empty().defaultIfEmpty("默认值").block());
        //4.5
        Mono<String> m1 = Mono.just("确定值");
        String nullable=null;
        Mono<Object> empty = Mono.justOrEmpty(nullable);
        Mono<String> stringMono = Mono.fromCallable(() -> "提供的值");
        Mono<Object> objectMono = Mono.fromCompletionStage(CompletableFuture::new);
        Mono<Object> error = Mono.error(() -> {
            throw new RuntimeException("出错啦～");
        });

        //Hello World
        //A错 B对！
    }
}
