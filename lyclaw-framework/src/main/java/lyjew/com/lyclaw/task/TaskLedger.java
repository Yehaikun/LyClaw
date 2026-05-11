package lyjew.com.lyclaw.task;

import java.util.List;
import java.util.Optional;

public interface TaskLedger {

    void addRecord(TaskRecord record);
    List<TaskRecord> getRecords(String taskId);
    Optional<TaskRecord> getLatestRecord(String taskId);
    List<TaskRecord> getAllTasks();
}
