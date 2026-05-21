package lyjew.com.lyclaw.persistence.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 异步写入队列注册表——管理所有活跃Session的AsyncWriteQueue实例。
 *
 * 使用ConcurrentHashMap保证线程安全。getOrCreate通过computeIfAbsent保证
 * 同一sessionId只创建一次队列实例。shutdown()在应用关闭时由@PreDestroy调用。
 */
public class AsyncWriteQueueRegistry {

    private final ConcurrentMap<String, AsyncWriteQueue> queues = new ConcurrentHashMap<>();

    /**
     * 获取或创建sessionId对应的队列实例。
     * @param sessionId 会话ID
     * @param factory 队列工厂函数（仅在首次创建时调用）
     * @return 已存在或新创建的AsyncWriteQueue实例
     */
    public AsyncWriteQueue getOrCreate(String sessionId,
                                        java.util.function.Supplier<AsyncWriteQueue> factory) {
        return queues.computeIfAbsent(sessionId, k -> factory.get());
    }

    /** 移除并关闭指定会话的写入队列 */
    public void remove(String sessionId) {
        AsyncWriteQueue queue = queues.remove(sessionId);
        if (queue != null) queue.close();
    }

    /** 关闭所有队列（应用关闭时调用），清空注册表 */
    public void shutdown() {
        queues.values().forEach(AsyncWriteQueue::close);
        queues.clear();
    }
}
