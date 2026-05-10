package lyjew.com.lyclaw.task;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReviseRequest {
    private TaskPlan currentPlan;
    private String feedback;
    private String reason;
}
