package lyjew.com.lyclaw.reflect.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.ReflectionProperties;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.reflect.condition.AlwaysPassEvaluator;
import lyjew.com.lyclaw.reflect.condition.ConditionEvaluatorRegistry;
import lyjew.com.lyclaw.reflect.condition.EvaluationResultEvaluator;
import lyjew.com.lyclaw.reflect.condition.RetrievalDecisionEvaluator;
import lyjew.com.lyclaw.reflect.condition.RouteDecisionEvaluator;
import lyjew.com.lyclaw.reflect.condition.ScoreThresholdEvaluator;
import lyjew.com.lyclaw.reflect.impl.TopologyExecutor;
import lyjew.com.lyclaw.reflect.impl.actor.ReActActor;
import lyjew.com.lyclaw.reflect.impl.actor.SimpleChatActor;
import lyjew.com.lyclaw.reflect.impl.evaluator.*;
import lyjew.com.lyclaw.reflect.impl.hook.MemoryHookRegistry;
import lyjew.com.lyclaw.reflect.impl.hook.ReflectionPersistenceHook;
import lyjew.com.lyclaw.reflect.impl.reflector.VerbalReflector;
import lyjew.com.lyclaw.reflect.impl.store.InMemoryReflectionStore;
import lyjew.com.lyclaw.reflect.topology.TopologyLoader;
import lyjew.com.lyclaw.reflect.impl.router.FixedIterRouter;
import lyjew.com.lyclaw.reflect.impl.router.LLMRouter;
import lyjew.com.lyclaw.reflect.impl.router.ThresholdRouter;
import lyjew.com.lyclaw.reflect.impl.synthesizer.BestScoreSynthesizer;
import lyjew.com.lyclaw.reflect.impl.synthesizer.LLMSynthesizer;
import lyjew.com.lyclaw.reflect.impl.synthesizer.LastOutputSynthesizer;
import lyjew.com.lyclaw.reflect.registry.PrimitiveFactory;
import lyjew.com.lyclaw.reflect.registry.ReflectionTopologyRegistry;
import lyjew.com.lyclaw.reflect.topology.*;
import lyjew.com.lyclaw.tool.ToolRegistry;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 反射模块自动装配 — 创建核心基础设施 Bean 并将已知原语实现注册到 PrimitiveFactory。
 *
 * <p>装配内容：
 * <ol>
 *   <li>注册中心：{@link PrimitiveFactory}、{@link ReflectionTopologyRegistry}</li>
 *   <li>执行引擎：{@link TopologyExecutor}</li>
 *   <li>原语实现：ReActActor、SimpleChatActor、LLMJudgeEvaluator、HeuristicEvaluator、ThresholdRouter、VerbalReflector</li>
 *   <li>原语注册：将所有原语 Bean 按 (PrimitiveType, name) 注册到 PrimitiveFactory 并设置默认实现</li>
 *   <li>默认拓扑：构建 passthrough（Actor→Synthesizer）作为全局兜底拓扑</li>
 * </ol>
 *
 * <p>条件装配：依赖 ChatFacade.class 在 classpath 中，确保 LLM 基础设施就绪后才生效。
 *
 * @see PrimitiveFactory
 * @see ReflectionTopologyRegistry
 * @see TopologyExecutor
 */
@AutoConfiguration
@ConditionalOnClass(ChatFacade.class)
@EnableConfigurationProperties(ReflectionProperties.class)
public class ReflectAutoConfiguration {

    // ═══════════════════════════════════════════════════════════════
    // 注册中心 Bean
    // ═══════════════════════════════════════════════════════════════

    @Bean
    public PrimitiveFactory primitiveFactory() {
        return new PrimitiveFactory();
    }

    @Bean
    public ReflectionTopologyRegistry topologyRegistry() {
        return new ReflectionTopologyRegistry();
    }

    @Bean
    public ConditionEvaluatorRegistry conditionEvaluatorRegistry() {
        return new ConditionEvaluatorRegistry(List.of(
                new RouteDecisionEvaluator(),
                new EvaluationResultEvaluator(),
                new ScoreThresholdEvaluator(),
                new lyjew.com.lyclaw.reflect.condition.ConsistencyEvaluator(),
                new RetrievalDecisionEvaluator(),
                new AlwaysPassEvaluator()
        ));
    }

    @Bean
    public TopologyExecutor topologyExecutor(PrimitiveFactory factory, MemoryHookRegistry hookRegistry,
                                              ConditionEvaluatorRegistry conditionRegistry) {
        return new TopologyExecutor(factory, hookRegistry, conditionRegistry);
    }

    // ═══════════════════════════════════════════════════════════════
    // 原语实现 Bean（构造器参数由 Spring 自动注入）
    // ═══════════════════════════════════════════════════════════════

    @Bean
    public ThresholdRouter thresholdRouter(ReflectionProperties props) {
        return new ThresholdRouter(props.getRouter().getThresholdDefault());
    }

    @Bean
    public HeuristicEvaluator heuristicEvaluator() {
        return new HeuristicEvaluator();
    }

    @Bean
    public SimpleChatActor simpleChatActor(ChatFacade chatFacade) {
        return new SimpleChatActor(chatFacade);
    }

    @Bean
    public ReActActor reActActor(ReActEngine reActEngine, ChatFacade chatFacade, ToolRegistry toolRegistry) {
        return new ReActActor(reActEngine, chatFacade, toolRegistry);
    }

    @Bean
    public LLMJudgeEvaluator llmJudgeEvaluator(ChatFacade chatFacade, ObjectMapper objectMapper,
                                                ReActEngine reActEngine, ToolRegistry toolRegistry) {
        return new LLMJudgeEvaluator(chatFacade, objectMapper, reActEngine, toolRegistry);
    }

    @Bean
    public VerbalReflector verbalReflector(ChatFacade chatFacade) {
        return new VerbalReflector(chatFacade);
    }

    @Bean
    public ImportanceEvaluator importanceEvaluator() {
        return new ImportanceEvaluator();
    }

    @Bean
    public ConsistencyEvaluator consistencyEvaluator() {
        return new ConsistencyEvaluator();
    }

    // ── P1 Router 扩展 ──

    @Bean
    public FixedIterRouter fixedIterRouter(ReflectionProperties props) {
        return new FixedIterRouter(props.getRouter().getFixedIterDefault());
    }

    @Bean
    public LLMRouter llmRouter(ChatFacade chatFacade) {
        return new LLMRouter(chatFacade);
    }

    // ── P1 Synthesizer 扩展 ──

    @Bean
    public LastOutputSynthesizer lastOutputSynthesizer() {
        return new LastOutputSynthesizer();
    }

    @Bean
    public BestScoreSynthesizer bestScoreSynthesizer() {
        return new BestScoreSynthesizer();
    }

    @Bean
    public LLMSynthesizer llmSynthesizer(ChatFacade chatFacade) {
        return new LLMSynthesizer(chatFacade);
    }

    // ── P1 Memory + Hook ──

    @Bean
    public InMemoryReflectionStore reflectionStore() {
        return new InMemoryReflectionStore();
    }

    @Bean
    public MemoryHookRegistry memoryHookRegistry() {
        return new MemoryHookRegistry();
    }

    @Bean
    public ReflectionPersistenceHook reflectionPersistenceHook() {
        return new ReflectionPersistenceHook();
    }

    @Bean
    public TopologyLoader topologyLoader(ReflectionTopologyRegistry registry) {
        return new TopologyLoader(registry);
    }

    // ═══════════════════════════════════════════════════════════════
    // 原语自注册：BeanPostProcessor 扫描 @Primitive 注解自动注册
    // ═══════════════════════════════════════════════════════════════

    /**
     * 扫描所有标注 {@link lyjew.com.lyclaw.annotation.reflect.Primitive @Primitive} 的 Bean，
     * 在初始化后自动注册到 {@link PrimitiveFactory}，替代原先手动逐个注册的方式。
     */
    @Bean
    public static PrimitiveRegistrarPostProcessor primitiveRegistrarPostProcessor(PrimitiveFactory factory) {
        return new PrimitiveRegistrarPostProcessor(factory);
    }

    /**
     * 注册非 Spring Bean 的原语实例（ToolVerifierEvaluator 通过静态工厂创建，不走 BeanPostProcessor）。
     */
    @Bean
    public Object toolVerifierRegistrar(PrimitiveFactory factory) {
        factory.register(PrimitiveType.EVALUATOR, "toolVerifierExitCode", ToolVerifierEvaluator.exitCode());
        factory.register(PrimitiveType.EVALUATOR, "toolVerifierTestSuite", ToolVerifierEvaluator.testSuite());
        return "toolVerifierRegistrarDone";
    }

    // ═══════════════════════════════════════════════════════════════
    // 默认拓扑注册
    // ═══════════════════════════════════════════════════════════════

    /**
     * 注册 passthrough 默认拓扑 — 单 Actor → Synthesizer 直通链路。
     * 当 agent 未配置 @ReflectionConfig 或拓扑未命中时，回退到此拓扑，
     * 行为等同于原 RespondStage 的简单 LLM 调用。
     */
    @Bean
    public Object defaultTopologyRegistrar(ReflectionTopologyRegistry registry) {
        ReflectionTopology passthrough = TopologyPresets.passthrough();
        registry.setDefaultTopology(passthrough);

        // 注册三大著名反思拓扑用于集成测试
        registry.register("reflexion-test", TopologyPresets.reflexion());
        registry.register("self-refine-test", TopologyPresets.selfRefine());
        registry.register("critic-test", TopologyPresets.critic());

        return "defaultTopologyRegistrarDone";
    }

    // ═══════════════════════════════════════════════════════════════
    // MemoryHook 注册
    // ═══════════════════════════════════════════════════════════════

    @Bean
    public Object memoryHookRegistrar(MemoryHookRegistry registry,
                                       ReflectionPersistenceHook persistenceHook) {
        registry.register(persistenceHook);
        return "memoryHookRegistrarDone";
    }
}
