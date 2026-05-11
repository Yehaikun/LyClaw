package lyjew.com.lyclaw.agent.collab;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AssignmentPlan {

    @Data
    @Builder
    public static class Assignment {
        private String agentId;
        private String taskNodeId;
        private String role;
        private int priority;
    }

    private List<Assignment> assignments;
    private Map<String, List<String>> communicationChannels;
}
