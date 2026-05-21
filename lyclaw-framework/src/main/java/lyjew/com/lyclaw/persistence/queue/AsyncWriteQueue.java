package lyjew.com.lyclaw.persistence.queue;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.repository.SessionRepository;

/**
 * Per-Session异步写入队列。
 *
 * 每个活跃Session一个AsyncWriteQueue实例。单消费者daemon线程FIFO消费BlockingQueue，
 * 保证同一会话内消息写入的严格顺序。enqueue()立即返回不阻塞ReAct循环，
 * 实际IO在后台线程完成。失败重试最多3次，连续失败后降级（日志告警但不停服）。
 *
 * 使用有界LinkedBlockingQueue防止内存溢出，容量由StorageProperties控制。
 * 重试缓冲区retryBuffer保留上次失败任务，下次enqueue时优先重放。
 */
public class AsyncWriteQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncWriteQueue.class);

    private final String sessionId;
    private final SessionRepository sessionRepository;
    private final BlockingQueue<WriteTask> queue;
    private final Thread consumerThread;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final int maxRetries;
    private volatile boolean running = true;

    /** 重试缓冲区：上次写入失败的消息，下次写入时优先flush */
    private WriteTask retryBuffer;

    /**
     * 创建异步写入队列并启动后台消费者线程。
     * @param sessionId 会话ID（用于线程命名和日志）
     * @param sessionRepository 底层持久化操作
     * @param capacity 队列容量（有界队列，超出时调用者线程阻塞等待）
     */
    public AsyncWriteQueue(String sessionId, SessionRepository sessionRepository, int capacity) {
        this.sessionId = sessionId;
        this.sessionRepository = sessionRepository;
        this.maxRetries = 3;
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.consumerThread = new Thread(this::consumeLoop, "jsonl-writer-" + sessionId);
        this.consumerThread.setDaemon(true);
        this.consumerThread.start();
    }

    /**
     * 投递消息行到队列，立即返回（不阻塞ReAct循环）。
     * 如果retryBuffer中有上次失败的任务，优先将其放入队列头部。
     */
    public void enqueue(Session session, Map<String, Object> messageFields) {
        if (!running) return;
        // 先flush重试缓冲
        WriteTask retry = retryBuffer;
        if (retry != null) {
            retryBuffer = null;
            queue.add(retry);
        }
        queue.add(new WriteTask(session, messageFields, false));
    }

    /** 投递compaction事件行——与消息行共用同一队列，保证时序正确 */
    public void enqueueCompaction(String filePath, int messagesCompacted,
                                   int summaryTokens, double qualityScore) {
        if (!running) return;
        queue.add(new WriteTask(null,
                Map.of("type", "compaction", "messagesCompacted", messagesCompacted,
                       "summaryTokens", summaryTokens, "qualityScore", qualityScore,
                       "timestamp", System.currentTimeMillis()),
                true));
    }

    /**
     * 消费者主循环——阻塞等待队列任务，逐条写入JSONL+SQLite。
     * 连续失败达到maxRetries后记录ERROR日志并丢弃该消息（降级策略）。
     */
    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                WriteTask task = queue.poll(1, TimeUnit.SECONDS);
                if (task == null) continue;
                try {
                    if (!task.isCompaction) {
                        sessionRepository.appendMessage(task.session, task.fields);
                    }
                    consecutiveFailures.set(0);  // 成功后重置失败计数
                } catch (Exception e) {
                    int failures = consecutiveFailures.incrementAndGet();
                    log.error("JSONL写入失败 (session={}, 连续失败={}/3): {}",
                            sessionId, failures, e.getMessage());
                    if (failures < maxRetries) {
                        retryBuffer = task;  // 下次重试
                    } else {
                        log.error("Session {} 持久化降级，连续失败超过3次，丢弃消息", sessionId);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        consumerThread.interrupt();
    }

    /** 内部任务封装——区分普通消息写入和compaction事件写入 */
    private record WriteTask(Session session, Map<String, Object> fields, boolean isCompaction) {}
}
