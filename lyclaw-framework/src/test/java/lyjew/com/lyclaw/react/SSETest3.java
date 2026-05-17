package lyjew.com.lyclaw.react;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SSETest3 {
    public static void main(String[] args) throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<String> future = new CompletableFuture<>();
        System.out.println(future.isDone());
        try {
            System.out.println(future.get(1, TimeUnit.SECONDS));
        }catch (Exception e){
            System.out.println("没等到，还没完成呢");
        }
        System.out.println(future.complete("完成！"));
        System.out.println(future.isDone());
        System.out.println(future.get(1, TimeUnit.SECONDS));
    }
}
