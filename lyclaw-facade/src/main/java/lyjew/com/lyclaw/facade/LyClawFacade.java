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

/**
 * LyClaw系统的外观(Facade)入口，对外暴露统一的API调用接口。
 *
 * <p>
 * 聚合编排器(Orchestrator)、模型提供者(ModelProvider)、工具注册表(ToolRegistry)、
 * 会话存储(SessionStorage)、配置存储(ConfigStorage)和记忆系统(MemorySystem)，
 * 为上层调用方提供简化的访问入口。
 * </p>
 *
 * <p>
 * 采用Facade设计模式，将底层多个子系统的复杂交互封装在高阶API中，
 * 统一管理对话编排、Agent任务执行、资源CRUD和模型配置等核心能力。
 * </p>
 *
 * @author lyjew
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LyClawFacade {

    /** 编排引擎，负责多Agent协作任务的调度执行 */
    private final Orchestrator orchestrator;
    /** 模型提供商，管理多种AI模型适配器 */
    private final ModelProvider modelProvider;
    /** 工具注册表，管理所有可用的Tool/Skill定义 */
    private final ToolRegistry toolRegistry;
    /** 会话存储，持久化会话状态和历史 */
    private final SessionStorage sessionStorage;
    /** 配置存储，持久化模型配置 */
    private final ConfigStorage configStorage;
    /** 记忆系统，管理长期/短期记忆 */
    private final MemorySystem memorySystem;

    /**
     * 处理聊天请求，构建上下文并交给编排器执行。
     *
     * @param request 聊天请求
     * @return SSE事件流
     */
    public Flux<ServerSentEvent<String>> chat(ChatRequest request) {
        log.info("Processing chat: session={}", request.getSessionId());
        ChatContext context = buildContext(request);
        return orchestrator.execute(context);
    }

    /**
     * 执行Agent任务。
     *
     * @param context 编排上下文
     * @return Agent事件流
     */
    public Flux<AgentEvent> agentTask(OrchestrationContext context) {
        log.info("Processing agent task: mode={}", context.getCollaborationModeId());
        return orchestrator.executeAgentTask(context);
    }

    /** @return 所有会话列表 */
    public List<Session> getSessions() {
        return sessionStorage.getAll();
    }

    /** @return 所有可用的模型提供者名称集合 */
    public Set<String> getProviders() {
        return modelProvider.listProviders();
    }

    /** @return 所有已注册的工具定义列表 */
    public List<ToolDefinition> getTools() {
        return toolRegistry.getAllDefinitions();
    }

    /**
     * 配置模型参数，保存配置并应用到对应的模型适配器。
     *
     * @param config 模型配置
     */
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

    /**
     * 按ID获取会话。
     *
     * @param id 会话ID
     * @return 会话对象，不存在时返回null
     */
    public Session getSession(String id) {
        return sessionStorage.get(id).orElse(null);
    }

    /**
     * 删除指定会话。
     *
     * @param id 会话ID
     */
    public void deleteSession(String id) {
        sessionStorage.delete(id);
        log.info("Session deleted: {}", id);
    }

    /** @return 所有模型配置列表 */
    public List<ModelConfig> getModelConfigs() {
        return configStorage.getAll();
    }

    /** @return 记忆系统统计信息 */
    public MemoryStats getMemoryStats() {
        return memorySystem.getStats();
    }

    /**
     * 根据请求构建聊天上下文，如果会话不存在则自动创建。
     *
     * @param request 聊天请求
     * @return 构建好的聊天上下文
     */
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
