package lyjew.com.lyclaw.task;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

@JsonDeserialize(as = SimpleTaskPlan.class)
public interface TaskPlan {

    List<TaskNode> getNodes();
    List<String> getDependencies(String nodeId);
    long getEstimatedCompletionTime();
    boolean isReady();
}
