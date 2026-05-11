package lyjew.com.lyclaw.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计划修订请求实体，封装修订任务计划所需的当前计划、反馈和修改原因。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviseRequest {
    private TaskPlan currentPlan;
    private String feedback;
    private String reason;
}
