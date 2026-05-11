package lyjew.com.lyclaw.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 计划请求实体，封装触发任务规划所需的全部信息。
 * 包含会话ID、用户意图、使用的分解策略和额外上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanRequest {
    private String sessionId;
    private String userIntent;
    private String strategy;
    private Map<String, Object> context;
}
