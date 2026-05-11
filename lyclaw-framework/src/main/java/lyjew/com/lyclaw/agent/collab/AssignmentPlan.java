package lyjew.com.lyclaw.agent.collab;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 协作分配计划，描述一次多代理协作中每个代理的任务分配方案。
 *
 * AssignmentPlan 由协作模式根据当前可用的代理池和编排上下文生成，
 * 包含了每个代理被分配的细分任务以及代理间的通信拓扑。每个代理的分配
 * 记录（Assignment）指定了代理 ID、任务节点、角色和优先级，协调器
 * 据此将对应的子任务下发到各代理。communicationChannels 定义了代理
 * 之间允许的通信链路，确保代理只与计划内的对等方交互，避免冗余通信。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class AssignmentPlan {

    /**
     * 单项任务分配记录，描述一个代理被分派的子任务信息。
     *
     * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
     */
    @Data
    @Builder
    public static class Assignment {
        /** 被分配的代理唯一标识 */
        private String agentId;
        /** 任务图中对应的任务节点标识 */
        private String taskNodeId;
        /** 代理在协作中的角色，如 "leader"、"worker" */
        private String role;
        /** 分配的优先级，数值越小优先级越高 */
        private int priority;
    }

    /** 所有代理的任务分配列表 */
    private List<Assignment> assignments;
    /** 代理间允许的通信链路：key 为代理 ID，value 为其可通信的代理 ID 列表 */
    private Map<String, List<String>> communicationChannels;
}
