package lyjew.com.lyclaw.session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import lyjew.com.lyclaw.model.Message;

/**
 * 进程内内存消息存储 —— 消息以追加方式存储在 CopyOnWriteArrayList 中。
 *
 * <p>线程安全，适合 demo 和单进程测试。进程重启消息丢失。</p>
 */
public class InMemoryMessageStore implements MessageStore {

    private final ConcurrentHashMap<String, List<Message>> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public int append(String sessionId, Message message) {
        List<Message> messages = store.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        messages.add(message);
        return counters.computeIfAbsent(sessionId, k -> new AtomicInteger(0)).incrementAndGet() - 1;
    }

    @Override
    public int[] appendBatch(String sessionId, List<Message> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) {
            return new int[0];
        }
        List<Message> messages = store.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        AtomicInteger counter = counters.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        int[] indices = new int[newMessages.size()];
        for (int i = 0; i < newMessages.size(); i++) {
            messages.add(newMessages.get(i));
            indices[i] = counter.getAndIncrement();
        }
        return indices;
    }

    @Override
    public List<Message> load(String sessionId, int offset, int limit) {
        List<Message> messages = store.get(sessionId);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, offset);
        if (from >= messages.size()) {
            return List.of();
        }
        int to = limit > 0 ? Math.min(messages.size(), from + limit) : messages.size();
        return new ArrayList<>(messages.subList(from, to));
    }

    @Override
    public List<Message> loadLatest(String sessionId, int lastN) {
        List<Message> messages = store.get(sessionId);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int effectiveLastN = Math.max(1, lastN);
        int from = Math.max(0, messages.size() - effectiveLastN);
        return new ArrayList<>(messages.subList(from, messages.size()));
    }

    @Override
    public List<Message> loadSince(String sessionId, int afterIndex) {
        List<Message> messages = store.get(sessionId);
        if (messages == null || afterIndex < 0 || afterIndex >= messages.size() - 1) {
            return List.of();
        }
        return new ArrayList<>(messages.subList(afterIndex + 1, messages.size()));
    }

    @Override
    public void updateContent(String sessionId, int index, String content) {
        List<Message> messages = store.get(sessionId);
        if (messages != null && index >= 0 && index < messages.size()) {
            // CopyOnWriteArrayList.set() is atomic
            Message existing = messages.get(index);
            messages.set(index, Message.builder()
                    .role(existing.getRole())
                    .content(content)
                    .toolCallId(existing.getToolCallId())
                    .toolName(existing.getToolName())
                    .thinking(existing.getThinking())
                    .toolCalls(existing.getToolCalls())
                    .build());
        }
    }

    @Override
    public int pruneBefore(String sessionId, int keepLastN) {
        List<Message> messages = store.get(sessionId);
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        if (messages.size() <= keepLastN) {
            return 0;
        }
        int removeCount = messages.size() - keepLastN;
        List<Message> kept = new ArrayList<>(messages.subList(removeCount, messages.size()));
        store.put(sessionId, kept);
        return removeCount;
    }

    @Override
    public void deleteBySession(String sessionId) {
        store.remove(sessionId);
        counters.remove(sessionId);
    }

    @Override
    public int count(String sessionId) {
        List<Message> messages = store.get(sessionId);
        return messages != null ? messages.size() : 0;
    }
}
