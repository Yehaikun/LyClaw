package lyjew.com.lyclaw.react;

import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;

public class SSETest4 {
    public static void main(String[] args) throws InterruptedException {
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            try{
                Thread.sleep(3000);
            }catch (Exception e){

            }
            return  42;});
        Mono<Integer> mono = Mono.fromFuture(future);
        mono.subscribe(System.out::println);
        Thread.sleep(4000);
        /**
         * CompletableFuture.supplyAsync(() -> getUserName(userId))
         * .thenCompose()
         */
    }
}
