package lyjew.com.lyclaw.agent.collab;

import java.util.List;
import java.util.Optional;

/**
 * 协作中心接口，负责管理和发现可用的协作模式。
 *
 * CollaborationHub 是协作模式的注册表，系统启动时各种协作模式（如
 * 辩论模式、投票模式、链式模式）会注册到 Hub 中。当编排器需要组织
 * 多代理协作时，通过 Hub 查询合适的模式。支持按模式 ID 精确查找、
 * 全量列举、以及按网络拓扑类型过滤兼容模式，从而让系统自动选择
 * 最适合当前任务结构的协作方式。
 */
public interface CollaborationHub {

    /**
     * 注册一个新的协作模式。
     *
     * @param mode 待注册的协作模式实例
     */
    void register(CollaborationMode mode);

    /**
     * 根据模式 ID 获取对应的协作模式。
     *
     * @param modeId 协作模式的唯一标识
     * @return 如果找到则返回 Optional 包装的模式，否则返回 empty
     */
    Optional<CollaborationMode> getMode(String modeId);

    /**
     * 列出所有已注册的协作模式。
     *
     * @return 所有已注册模式的列表
     */
    List<CollaborationMode> listModes();

    /**
     * 查找所有兼容指定网络拓扑类型的协作模式。
     *
     * @param topology 目标网络拓扑类型（如 STAR、MESH）
     * @return 兼容该拓扑的模式列表
     */
    List<CollaborationMode> findCompatible(TopologyType topology);
}
