package lyjew.com.lyclaw.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 技能执行请求，封装调用预定义技能所需的全部参数。
 *
 * <p>技能是框架中比工具更高层级的抽象，通常由多个工具调用步骤组合而成。
 * 该类用于将技能标识、会话信息和执行参数统一传递给技能执行器。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillExecuteRequest {
    /** 技能标识，对应框架中注册的技能 ID */
    private String skillId;
    /** 当前会话标识，用于关联执行上下文 */
    private String sessionId;
    /** 技能执行参数，键值对形式传递 */
    private Map<String, Object> params;
}
