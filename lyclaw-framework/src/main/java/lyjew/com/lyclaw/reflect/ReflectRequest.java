package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 反思请求实体，封装触发反思引擎执行所需的全部信息。
 * 包含会话标识、模型输出、期望输出和上下文信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReflectRequest {
    private String sessionId;
    private String output;
    private String expectedOutput;
    private String context;
}
