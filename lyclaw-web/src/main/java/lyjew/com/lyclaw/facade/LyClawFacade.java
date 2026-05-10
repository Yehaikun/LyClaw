package lyjew.com.lyclaw.facade;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.engine.Engine;
import lyjew.com.lyclaw.engine.EngineSelector;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.ConfigStorage;
import lyjew.com.lyclaw.storage.SessionStorage;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

/**
 * LyClawFacade -- unified entry point for all LyClaw operations.
 *
 * <p>Provides a simplified API for controllers to interact with the backend.
 * Hides the complexity of engine selection, pipeline execution, and storage.</p>
 *
 * @since 1.0
 */
@Slf4j
@Component
public class LyClawFacade {

    private final EngineSelector engineSelector;
    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final SessionStorage sessionStorage;
    private final ConfigStorage configStorage;
    private final MemoryManager memoryManager;
    private final EventBus eventBus;

    public LyClawFacade(EngineSelector engineSelector,
                        ModelProvider modelProvider,
                        ToolRegistry toolRegistry,
                        SessionStorage sessionStorage,
                        ConfigStorage configStorage,
                        MemoryManager memoryManager,
                        EventBus eventBus) {
        this.engineSelector = engineSelector;
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.sessionStorage = sessionStorage;
        this.configStorage = configStorage;
        this.memoryManager = memoryManager;
        this.eventBus = eventBus;
    }

    /**
     * Execute a chat request and return a reactive stream of SSE-formatted strings.
     */
    public Flux<String> chat(ChatRequest request) {
        Engine engine = engineSelector.select(request);
        if (engine == null) {
            return Flux.error(new IllegalStateException("No engine available for this request"));
        }
        log.info("LyClawFacade: selected engine [{}] for request", engine.getName());
        return engine.execute(request);
    }

    /**
     * List all sessions.
     */
    public List<Session> getSessions() {
        return sessionStorage.getAll();
    }

    /**
     * List all available model providers.
     */
    public Set<String> getProviders() {
        return modelProvider.listProviders();
    }

    /**
     * List all registered tools.
     */
    public List<ToolDefinition> getTools() {
        return toolRegistry.getAllDefinitions();
    }

    /**
     * Save a model configuration and configure the adapter.
     */
    public void configureModel(ModelConfig config) {
        configStorage.save(config);
        ModelAdapter adapter = modelProvider.getAdapter(config.getProvider());
        adapter.configure(config);
        log.info("Model configured: provider={}, model={}", config.getProvider(), config.getModel());
    }

    /**
     * Get a session by ID.
     */
    public Session getSession(String id) {
        return sessionStorage.get(id).orElse(null);
    }

    /**
     * Delete a session by ID.
     */
    public void deleteSession(String id) {
        sessionStorage.delete(id);
        log.info("Session deleted: {}", id);
    }

    /**
     * Get all model configurations.
     */
    public List<ModelConfig> getModelConfigs() {
        return configStorage.getAll();
    }
}
