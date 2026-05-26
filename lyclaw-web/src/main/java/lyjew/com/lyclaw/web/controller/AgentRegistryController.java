package lyjew.com.lyclaw.web.controller;

import lyjew.com.lyclaw.action.agent.AgentLifecycleImpl;
import lyjew.com.lyclaw.action.agent.DefaultAgentRegistry;
import lyjew.com.lyclaw.agent.AgentCollaborationMode;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentHandle.HealthStatus;
import lyjew.com.lyclaw.agent.AgentSpec;
import lyjew.com.lyclaw.agent.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agents")
public class AgentRegistryController {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistryController.class);

    private final DefaultAgentRegistry registry;
    private final AgentLifecycleImpl lifecycle;

    public AgentRegistryController(DefaultAgentRegistry registry, AgentLifecycleImpl lifecycle) {
        this.registry = registry;
        this.lifecycle = lifecycle;
    }

    @GetMapping
    public List<AgentHandle> listAgents(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String capability) {
        List<AgentHandle> all = registry.getAllAgents();
        if (state != null && !state.isEmpty()) {
            AgentState filterState = AgentState.valueOf(state.toUpperCase());
            all = all.stream().filter(h -> h.getState() == filterState).collect(Collectors.toList());
        }
        if (capability != null && !capability.isEmpty()) {
            all = all.stream()
                    .filter(h -> h.getCapabilities() != null && h.getCapabilities().contains(capability))
                    .collect(Collectors.toList());
        }
        return all;
    }

    @GetMapping("/{agentId}")
    public ResponseEntity<AgentHandle> getAgent(@PathVariable String agentId) {
        return registry.lookup(agentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AgentHandle> createAgent(@RequestBody Map<String, Object> body) {
        String name = (String) body.getOrDefault("name", "unnamed");
        String description = (String) body.getOrDefault("description", "");
        @SuppressWarnings("unchecked")
        List<String> capabilities = (List<String>) body.getOrDefault("capabilities", Collections.emptyList());
        String model = (String) body.getOrDefault("model", "deepseek-chat");

        AgentSpec spec = AgentSpec.builder()
                .name(name)
                .description(description)
                .capabilities(capabilities)
                .modelName(model)
                .config(Collections.emptyMap())
                .build();

        try {
            AgentHandle handle = lifecycle.create(spec).join();
            return ResponseEntity.status(HttpStatus.CREATED).body(handle);
        } catch (Exception e) {
            log.error("Failed to create agent", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{agentId}")
    public ResponseEntity<AgentHandle> updateAgent(@PathVariable String agentId,
                                                    @RequestBody Map<String, Object> body) {
        Optional<AgentHandle> opt = registry.lookup(agentId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        AgentHandle handle = opt.get();
        if (body.containsKey("name")) handle.setName((String) body.get("name"));
        if (body.containsKey("description")) handle.setDescription((String) body.get("description"));
        if (body.containsKey("model")) handle.setModel((String) body.get("model"));
        if (body.containsKey("provider")) handle.setProvider((String) body.get("provider"));
        if (body.containsKey("systemPrompt")) handle.setSystemPrompt((String) body.get("systemPrompt"));
        @SuppressWarnings("unchecked")
        List<String> capabilities = (List<String>) body.get("capabilities");
        if (capabilities != null) handle.setCapabilities(capabilities);
        if (body.containsKey("collaborationMode")) {
            handle.setCollaborationMode(AgentCollaborationMode.valueOf(
                    ((String) body.get("collaborationMode")).toUpperCase()));
        }

        registry.recordHeartbeat(agentId);
        return ResponseEntity.ok(handle);
    }

    @DeleteMapping("/{agentId}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String agentId) {
        if (registry.lookup(agentId).isEmpty()) return ResponseEntity.notFound().build();
        lifecycle.terminate(agentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{agentId}/health")
    public ResponseEntity<Map<String, Object>> getHealth(@PathVariable String agentId) {
        return registry.lookup(agentId).map(handle -> {
            HealthStatus health = registry.getHealth(agentId);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("agentId", agentId);
            result.put("health", health.name());
            result.put("state", handle.getState().name());
            result.put("lastActiveAt", handle.getLastActiveAt() != null
                    ? handle.getLastActiveAt().toString() : null);
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{agentId}/pause")
    public ResponseEntity<Void> pauseAgent(@PathVariable String agentId) {
        boolean ok = lifecycle.pause(agentId);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{agentId}/resume")
    public ResponseEntity<Void> resumeAgent(@PathVariable String agentId) {
        boolean ok = lifecycle.resume(agentId);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{agentId}/terminate")
    public ResponseEntity<Void> terminateAgent(@PathVariable String agentId) {
        boolean ok = lifecycle.terminate(agentId);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/{agentId}/capabilities")
    public ResponseEntity<List<String>> getCapabilities(@PathVariable String agentId) {
        return registry.lookup(agentId)
                .map(h -> ResponseEntity.ok(
                        h.getCapabilities() != null ? h.getCapabilities() : Collections.emptyList()))
                .orElse(ResponseEntity.notFound().build());
    }
}
