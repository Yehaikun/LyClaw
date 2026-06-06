package lyjew.com.lyclaw.mesh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Agent 执行事件存储 —— 环形缓冲，每个 Agent 保留最近 N 条事件。
 *
 * <p>前端可以通过 REST API 查询历史事件，也可以先连 SSE 再查历史补全。</p>
 */
public class AgentExecutionStore {

    private static final int DEFAULT_MAX_EVENTS_PER_AGENT = 200;

    private final int maxEventsPerAgent;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<AgentExecutionEvent>> store = new ConcurrentHashMap<>();

    /** SSE 订阅者 */
    private final ConcurrentLinkedDeque<java.util.function.Consumer<AgentExecutionEvent>> subscribers = new ConcurrentLinkedDeque<>();

    public AgentExecutionStore() { this(DEFAULT_MAX_EVENTS_PER_AGENT); }

    public AgentExecutionStore(int maxEventsPerAgent) {
        this.maxEventsPerAgent = maxEventsPerAgent;
    }

    /** 追加事件 */
    public void append(AgentExecutionEvent event) {
        ConcurrentLinkedDeque<AgentExecutionEvent> queue =
                store.computeIfAbsent(event.getAgentId(), k -> new ConcurrentLinkedDeque<>());
        queue.addFirst(event);
        while (queue.size() > maxEventsPerAgent) {
            queue.pollLast();
        }
        // 推送给所有 SSE 订阅者
        for (var sub : subscribers) {
            try { sub.accept(event); } catch (Exception ignored) {}
        }
    }

    /** 获取指定 Agent 的事件 */
    public List<AgentExecutionEvent> getEvents(String agentId, int limit) {
        ConcurrentLinkedDeque<AgentExecutionEvent> queue = store.get(agentId);
        if (queue == null) return List.of();
        int effectiveLimit = Math.min(limit, queue.size());
        List<AgentExecutionEvent> result = new ArrayList<>();
        var it = queue.iterator();
        for (int i = 0; i < effectiveLimit && it.hasNext(); i++) {
            result.add(it.next());
        }
        return result;
    }

    /** 获取所有 Agent 的最新事件 */
    public Map<String, AgentExecutionEvent> getLatestForAll() {
        Map<String, AgentExecutionEvent> result = new LinkedHashMap<>();
        for (Map.Entry<String, ConcurrentLinkedDeque<AgentExecutionEvent>> entry : store.entrySet()) {
            AgentExecutionEvent latest = entry.getValue().peekFirst();
            if (latest != null) result.put(entry.getKey(), latest);
        }
        return result;
    }

    /** 获取指定 Agent 的最新事件 */
    public AgentExecutionEvent getLatest(String agentId) {
        ConcurrentLinkedDeque<AgentExecutionEvent> queue = store.get(agentId);
        return queue != null ? queue.peekFirst() : null;
    }

    /** 注册 SSE 订阅者（收到事件时回调） */
    public void subscribe(java.util.function.Consumer<AgentExecutionEvent> subscriber) {
        subscribers.add(subscriber);
    }

    /** 注销 SSE 订阅者 */
    public void unsubscribe(java.util.function.Consumer<AgentExecutionEvent> subscriber) {
        subscribers.remove(subscriber);
    }

    /** 获取事件总数 */
    public int totalEvents() {
        return store.values().stream().mapToInt(ConcurrentLinkedDeque::size).sum();
    }
}
