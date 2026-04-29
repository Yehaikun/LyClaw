package lyjew.com.lyclaw.task;

import java.util.List;
import java.util.Optional;

/**
 * 任务账本接口 —— 记录所有任务执行历史，支持按任务 ID 查询。
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface TaskLedger {

    void addRecord(TaskRecord record);

    List<TaskRecord> getRecords(String taskId);

    Optional<TaskRecord> getLatestRecord(String taskId);

    List<TaskRecord> getAllTasks();
}