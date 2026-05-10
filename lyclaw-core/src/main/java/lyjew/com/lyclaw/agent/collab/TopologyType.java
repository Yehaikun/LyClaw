package lyjew.com.lyclaw.agent.collab;

/**
 * Agent 通信拓扑类型。
 *
 * @since 2.0
 */
public enum TopologyType {
    /** 星型: 一个中心节点 */
    STAR,
    /** 网状: 全互联 */
    MESH,
    /** 层次化: 树形结构 */
    HIERARCHICAL,
    /** 混合: 根据任务动态切换 */
    HYBRID
}
