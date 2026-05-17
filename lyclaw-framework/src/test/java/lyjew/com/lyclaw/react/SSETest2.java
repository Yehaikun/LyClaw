package lyjew.com.lyclaw.react;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SSETest2 {
    public static void main(String[] args) throws ExecutionException, InterruptedException, TimeoutException {
//        CompletableFuture<String> future = new CompletableFuture<>();
//        System.out.println(future.complete(33));
//        System.out.println(future.complete(4));
//        System.out.println(future.get());

//        new Thread(() -> {
//            try {
//                Thread.sleep(2000);
//            } catch (Exception e) {
//                System.out.println("任务报错了：" + e.getMessage());
//            }
//            future.complete("任务完成！");
//        }).start();
//        System.out.println("开始等待......");
//        try {
//            String s = future.get(1, TimeUnit.SECONDS);
//            System.out.println("收到结果：" + s);
//
//        } catch (Exception e) {
//            System.out.println("报错了：" + e.getClass());
//        }

        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            System.out.println("执行的线程是：" + Thread.currentThread().getName());
            System.out.println("我先睡会儿......");
            try {Thread.sleep(5000);}catch (Exception e){}
            return 42;
        });
        try {
            System.out.println("正在等待执行结果......");
            Integer i = future.get(6, TimeUnit.SECONDS);
            System.out.println("收到结果："+i);
        }catch (Exception e){
            System.out.println("出错了！");
        }


    }
}
