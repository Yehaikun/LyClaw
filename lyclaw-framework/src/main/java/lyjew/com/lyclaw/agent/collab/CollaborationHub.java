package lyjew.com.lyclaw.agent.collab;

import java.util.List;
import java.util.Optional;

public interface CollaborationHub {

    void register(CollaborationMode mode);
    Optional<CollaborationMode> getMode(String modeId);
    List<CollaborationMode> listModes();
    List<CollaborationMode> findCompatible(TopologyType topology);
}
