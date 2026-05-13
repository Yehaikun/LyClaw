package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.collab.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 协作模式中心（Hub）。
 *
 * 管理所有可用的 Agent 协作模式（market、network、pipeline、supervisor_worker）。
 * 在初始化时自动注册 Spring 容器中所有 CollaborationMode 实现。
 * 支持按模式 ID 查找、列出所有模式、以及根据拓扑类型筛选兼容模式。
 */
@Slf4j
@Service
public class CollaborationHubImpl implements CollaborationHub {

    /** 协作模式注册表：modeId -> CollaborationMode */
    private final ConcurrentHashMap<String, CollaborationMode> modeMap = new ConcurrentHashMap<>();

    /**
     * 构造时自动发现并注册所有 CollaborationMode Bean。
     *
     * @param modes Spring 自动注入的所有 CollaborationMode 实现
     */
    public CollaborationHubImpl(List<CollaborationMode> modes) {
        if (modes != null) {
            for (CollaborationMode mode : modes) {
                register(mode);
            }
        }
        log.info("[CollaborationHub] Initialized with {} collaboration modes: {}",
                modeMap.size(),
                modeMap.keySet().stream().sorted().collect(Collectors.toList()));
    }

    /**
     * 注册一个协作模式。相同 modeId 会覆盖旧实现。
     *
     * @param mode 要注册的协作模式
     */
    @Override
    public void register(CollaborationMode mode) {
        if (mode == null) {
            log.warn("[CollaborationHub] Attempted to register null mode, skipping");
            return;
        }
        String modeId = mode.getModeId();
        CollaborationMode existing = modeMap.put(modeId, mode);
        if (existing != null) {
            log.info("[CollaborationHub] Replaced existing mode: {} - {}", modeId,
                    existing.getClass().getSimpleName());
        } else {
            log.info("[CollaborationHub] Registered new mode: {} (topology={})",
                    modeId, mode.getPreferredTopology());
        }
    }

    /**
     * 按模式 ID 查找协作模式。
     *
     * @param modeId 模式标识（如 "market"、"network"）
     * @return 对应的 CollaborationMode，不存在时返回 empty
     */
    @Override
    public Optional<CollaborationMode> getMode(String modeId) {
        if (modeId == null || modeId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(modeMap.get(modeId));
    }

    /**
     * @return 所有已注册协作模式，按 modeId 排序
     */
    @Override
    public List<CollaborationMode> listModes() {
        return modeMap.values().stream()
                .sorted(Comparator.comparing(CollaborationMode::getModeId))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 查找与指定拓扑类型兼容的协作模式。
     * 兼容规则：首选拓扑匹配，或者模式支持 HYBRID（通用）拓扑。
     *
     * @param topology 拓扑类型（STAR、MESH、HIERARCHICAL、HYBRID）
     * @return 兼容模式列表
     */
    @Override
    public List<CollaborationMode> findCompatible(TopologyType topology) {
        if (topology == null) {
            return Collections.emptyList();
        }
        return modeMap.values().stream()
                .filter(m -> m.getPreferredTopology() == topology
                        || m.getPreferredTopology() == TopologyType.HYBRID)
                .sorted(Comparator.comparing(CollaborationMode::getModeId))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 获取所有可用协作模式的 ID 集合。
     *
     * <p>返回 modeMap 中所有已注册 CollaborationMode 的 modeId 的不可变视图
     *（Collections.unmodifiableSet）。当前系统中包含四种模式：
     * "market"（拍卖）、"network"（对等共识）、"pipeline"（流水线）、
     * "supervisor_worker"（监督者-工作者）。该集合可用于前端协作模式选择下拉菜单、
     * API 响应中列出可用模式，以及运维管理界面中展示系统能力。</p>
     *
     * @return 所有已注册模式 ID 的不可变集合，永远不会为 null
     */
    public Set<String> getAvailableModes() {
        return Collections.unmodifiableSet(modeMap.keySet());
    }

    /**
     * 获取已注册协作模式的总数。
     *
     * <p>返回 modeMap 的当前大小，反映系统中可用协作模式的数量。该数值在系统
     * 启动时确定（由构造函数自动注册所有 CollaborationMode Spring Bean），
     * 之后不会发生变化（除非通过 register() 方法动态注册新模式）。当前默认值
     * 为 4（market、network、pipeline、supervisor_worker），用于初始化日志
     * 确认和运维面板中的模式数量展示。</p>
     *
     * @return 已注册模式总数，最小为 0
     */
    public int getModeCount() {
        return modeMap.size();
    }
}
