package lyjew.com.lyclaw.task.impl;

import lyjew.com.lyclaw.task.TaskLedger;
import lyjew.com.lyclaw.task.TaskRecord;
import lyjew.com.lyclaw.task.TaskResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 默认任务账本 —— 基于内存的任务执行记录存储。
 *
 * <p><b>设计动机</b>：TaskLedger 是任务执行的"审计日志"。
 * 每次任务执行后都会写入一条 TaskRecord，包含执行时间、状态、结果等信息。
 * 默认实现使用内存存储（重启丢失），生产环境可替换为数据库存储。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TaskLedger
 */
@Component
public class DefaultTaskLedger implements TaskLedger {

    /**
     * 任务记录存储 —— taskId → TaskRecord 列表（按时间倒序排列）。
     *
     * <p>每个 taskId 可能有多次执行记录（如重试），所有记录按时间倒序存储。</p>
     */
    private final ConcurrentHashMap<String, List<TaskRecord>> records = new ConcurrentHashMap<>();

    /**
     * 添加一条任务执行记录。
     *
     * @param record 任务执行记录
     */
    @Override
    public void addRecord(TaskRecord record) {
        records.compute(record.getTaskId(), (key, list) -> {
            if (list == null) {
                // 首次执行 —— 创建新列表
                List<TaskRecord> newList = new ArrayList<>();
                newList.add(record);
                return newList;
            }
            // 已有记录 —— 追加到列表末尾
            list.add(record);
            return list;
        });
    }

    /**
     * 获取指定 taskId 的所有执行记录。
     *
     * @param taskId 任务 ID
     * @return 执行记录列表（按执行时间倒序），无记录时返回空列表
     */
    @Override
    public List<TaskRecord> getRecords(String taskId) {
        List<TaskRecord> list = records.get(taskId);
        if (list == null) {
            return Collections.emptyList();
        }
        // 按执行时间倒序排列
        List<TaskRecord> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> b.getStartedAt().compareTo(a.getStartedAt()));
        return Collections.unmodifiableList(sorted);
    }

    /**
     * 获取指定 taskId 的最新一条执行记录。
     *
     * @param taskId 任务 ID
     * @return 最新记录，无记录时返回空
     */
    @Override
    public Optional<TaskRecord> getLatestRecord(String taskId) {
        List<TaskRecord> list = records.get(taskId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        // 返回最后一条（最新添加的）
        return Optional.of(list.get(list.size() - 1));
    }

    /**
     * 获取所有任务的执行记录（按时间倒序）。
     *
     * @return 所有任务记录列表
     */
    @Override
    public List<TaskRecord> getAllTasks() {
        return records.values().stream()
                .flatMap(List::stream)
                .sorted((a, b) -> b.getStartedAt().compareTo(a.getStartedAt()))
                .collect(Collectors.toList());
    }
}