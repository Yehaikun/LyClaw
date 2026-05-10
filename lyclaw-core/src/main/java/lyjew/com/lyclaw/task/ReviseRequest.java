package lyjew.com.lyclaw.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviseRequest {
    private TaskPlan currentPlan;
    private String feedback;
    private String reason;
}
