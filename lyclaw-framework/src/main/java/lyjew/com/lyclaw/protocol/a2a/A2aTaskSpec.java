package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * A2A 协议中的任务规格实体，定义了一个待执行任务的完整参数。
 *
 * <p>TaskSpec 是 A2A 协议中发起任务请求时的核心载体。它包含任务的唯一标识、
 * 描述信息、输入制品列表（用于传递上游代理的输出作为本任务的输入）、
 * 自定义参数映射，以及任务级别的重试和超时配置。</p>
 *
 * <p>该实体由调用方构造并通过 {@link A2aGateway#sendTask} 发送给目标代理。</p>
 */
@Data
@Builder
public class A2aTaskSpec {
    /** 任务的唯一标识符 */
    private String taskId;
    /** 任务的自然语言描述，说明要执行什么操作 */
    private String description;
    /** 输入制品 ID 列表，引用其他代理的输出作为本任务的输入 */
    private List<String> inputArtifacts;
    /** 自定义参数映射，携带任务执行所需的额外配置 */
    private Map<String, Object> parameters;
    /** 最大重试次数，任务失败时的重试上限 */
    private int maxRetries;
    /** 超时时间（毫秒），任务执行的最长允许时间 */
    private long timeoutMs;
}
