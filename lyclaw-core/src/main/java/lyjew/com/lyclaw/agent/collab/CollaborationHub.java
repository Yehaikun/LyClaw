package lyjew.com.lyclaw.agent.collab;

import java.util.List;
import java.util.Optional;

/**
 * 协作中心 —— 自动发现并管理所有 CollaborationMode 实现。
 *
 * <p>新增模式只需 @Component + implements CollaborationMode,
 * CollaborationHub 通过 Spring 自动收集所有模式, 通过 getModeId() 路由。</p>
 *
 * @since 2.0
 */
public interface CollaborationHub {

    void register(CollaborationMode mode);

    Optional<CollaborationMode> getMode(String modeId);

    List<CollaborationMode> listModes();

    List<CollaborationMode> findCompatible(TopologyType topology);
}
