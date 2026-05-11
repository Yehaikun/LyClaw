package lyjew.com.lyclaw.action;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 动作执行结果，记录单个编排节点或工具调用的执行结果。
 *
 * <p>该类是编排流水线中每个节点的产出物，包含执行成功/失败状态、输出内容、
 * 错误信息、耗时以及可扩展的元数据。上层编排器根据 success 字段决定下一步编排分支。
 */
@Data
@Builder
public class ActionResult {
    /** 执行节点标识，对应编排流水线中的节点 ID */
    private String nodeId;
    /** 执行是否成功 */
    private boolean success;
    /** 成功时的输出内容 */
    private String output;
    /** 失败时的错误描述信息 */
    private String errorMessage;
    /** 执行耗时（毫秒） */
    private long durationMs;
    /** 可扩展的元数据，用于传递节点间的自定义信息 */
    private Map<String, Object> metadata;
}
