package lyjew.com.lyclaw.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryStats;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.orchestration.AgentEvent;
import lyjew.com.lyclaw.orchestration.OrchestrationContext;
import lyjew.com.lyclaw.orchestration.Orchestrator;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.ConfigStorage;
import lyjew.com.lyclaw.storage.SessionStorage;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class LyClawFacade {

    private final Orchestrator orchestrator;
    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final SessionStorage sessionStorage;
    private final ConfigStorage configStorage;
    private final MemorySystem memorySystem;

    public Flux<ServerSentEvent<String>> chat(ChatRequest request) {
        log.info("Processing chat: session={}", request.getSessionId());
        ChatContext context = buildContext(request);
        return orchestrator.execute(context);
    }

    public Flux<AgentEvent> agentTask(OrchestrationContext context) {
        log.info("Processing agent task: mode={}", context.getCollaborationModeId());
        return orchestrator.executeAgentTask(context);
    }

    public List<Session> getSessions() {
        return sessionStorage.getAll();
    }

    public Set<String> getProviders() {
        return modelProvider.listProviders();
    }

    public List<ToolDefinition> getTools() {
        return toolRegistry.getAllDefinitions();
    }

    public void configureModel(ModelConfig config) {
        configStorage.save(config);
        var adapter = modelProvider.getAdapter(config.getProvider());
        if (adapter != null) {
            adapter.configure(config);
            log.info("Model configured: provider={}, model={}", config.getProvider(), config.getModel());
        } else {
            log.warn("No adapter found for provider: {}", config.getProvider());
        }
    }

    public Session getSession(String id) {
        return sessionStorage.get(id).orElse(null);
    }

    public void deleteSession(String id) {
        sessionStorage.delete(id);
        log.info("Session deleted: {}", id);
    }

    public List<ModelConfig> getModelConfigs() {
        return configStorage.getAll();
    }

    public MemoryStats getMemoryStats() {
        return memorySystem.getStats();
    }

    private ChatContext buildContext(ChatRequest request) {
        Session session = sessionStorage.get(request.getSessionId())
                .orElseGet(() -> {
                    var newSession = Session.builder()
                            .id(request.getSessionId())
                            .build();
                    sessionStorage.save(newSession);
                    return newSession;
                });

        return new ChatContext(
                request,
                session,
                MemoryContent.empty(),
                toolRegistry.getAllDefinitions(),
                null,
                modelProvider
        );
    }
}
