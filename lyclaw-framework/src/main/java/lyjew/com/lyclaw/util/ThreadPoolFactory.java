package lyjew.com.lyclaw.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池工厂，提供统一命名的守护线程池创建方法。
 */
public final class ThreadPoolFactory {

    private ThreadPoolFactory() {}

    /** 创建固定大小的守护线程池，线程名前缀为 poolName。 */
    public static ExecutorService fixed(String poolName, int size) {
        return Executors.newFixedThreadPool(size, daemonFactory(poolName));
    }

    /** 创建虚拟线程池（Java 21+），线程名前缀为 poolName。 */
    public static ExecutorService virtual(String poolName) {
        return Executors.newThreadPerTaskExecutor(daemonFactory(poolName));
    }

    /** 创建带编号前缀的守护线程工厂。 */
    public static ThreadFactory daemonFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }
}
