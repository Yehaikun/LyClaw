package lyjew.com.lyclaw.task;

import java.util.List;
import java.util.Optional;

/**
 * 任务账本接口，负责记录和查询任务执行的历史记录。
 * 类似于审计日志，可追踪每个任务的完整执行轨迹。
 */
public interface TaskLedger {

    /**
     * 添加一条任务执行记录到账本中。
     *
     * @param record 任务记录
     */
    void addRecord(TaskRecord record);

    /**
     * 根据任务ID获取该任务的所有历史记录。
     *
     * @param taskId 任务ID
     * @return 历史记录列表
     */
    List<TaskRecord> getRecords(String taskId);

    /**
     * 根据任务ID获取最新的一条执行记录。
     *
     * @param taskId 任务ID
     * @return 最新的任务记录（可能为空）
     */
    Optional<TaskRecord> getLatestRecord(String taskId);

    /**
     * 获取所有任务的全部记录。
     *
     * @return 全部任务记录列表
     */
    List<TaskRecord> getAllTasks();
}
