package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.collab.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CollaborationHubImpl implements CollaborationHub {

    private final ConcurrentHashMap<String, CollaborationMode> modeMap = new ConcurrentHashMap<>();

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

    @Override
    public void register(CollaborationMode mode) {
        if (mode == null) {
            log.warn("[CollaborationHub] Attempted to register null mode, skipping");
            return;
        }
        String modeId = mode.getModeId();
        CollaborationMode existing = modeMap.put(modeId, mode);
        if (existing != null) {
            log.info("[CollaborationHub] Replaced existing mode: {} → {}", modeId,
                    existing.getClass().getSimpleName());
        } else {
            log.info("[CollaborationHub] Registered new mode: {} (topology={})",
                    modeId, mode.getPreferredTopology());
        }
    }

    @Override
    public Optional<CollaborationMode> getMode(String modeId) {
        if (modeId == null || modeId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(modeMap.get(modeId));
    }

    @Override
    public List<CollaborationMode> listModes() {
        return modeMap.values().stream()
                .sorted(Comparator.comparing(CollaborationMode::getModeId))
                .collect(Collectors.toUnmodifiableList());
    }

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

    public Set<String> getAvailableModes() {
        return Collections.unmodifiableSet(modeMap.keySet());
    }

    public int getModeCount() {
        return modeMap.size();
    }
}
