package lyjew.com.lyclaw.react;

import reactor.core.publisher.Flux;

import java.time.Duration;

public class SSETest7 {
    public static void main(String[] args) throws InterruptedException {
        //5.5
        Flux.range(1,20).filter((num)->num%2==0).map((num)->num*num).take(5)
                .subscribe(System.out::println);
        //顺序会变
        Flux<String> stream1=Flux.just("A1", "A2", "A3").delayElements(Duration.ofMillis(30));
        Flux<String> stream2=Flux.just("B1", "B2", "B3").delayElements(Duration.ofMillis(30));
        stream1.mergeWith(stream2).subscribe(System.out::println);
        Thread.sleep(100);

    }
}
