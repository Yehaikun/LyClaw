package lyjew.com.lyclaw.reflect.topology;

import java.util.Map;

/**
 * 预置反思拓扑工厂 — 提供 8 种业界著名的反思架构模板。
 *
 * <p>每种模板返回构建好的 {@link ReflectionTopology}，可直接注册到
 * {@link lyjew.com.lyclaw.reflect.registry.ReflectionTopologyRegistry} 或
 * 通过 {@code @ReflectionConfig(topology="...")} 引用。
 *
 * <p>模板概览：
 * <ol>
 *   <li><b>passthrough</b> — Actor→Synthesizer 单轮直通</li>
 *   <li><b>reflexion</b> — Actor→Evaluator→Router→Reflector 反思-重试循环</li>
 *   <li><b>selfRefine</b> — Actor→Evaluator→Router→Actor 自我修正（无独立Reflector）</li>
 *   <li><b>critic</b> — Actor→ToolVerifier→Router→Reflector 工具验证反馈</li>
 *   <li><b>multiEvaluator</b> — Actor→多Evaluator并行→Synthesizer 多维度评估</li>
 *   <li><b>memoryAugmented</b> — 带记忆检索的反思循环</li>
 * </ol>
 */
public final class TopologyPresets {

    private TopologyPresets() {} // 工具类，禁止实例化

    // ── 1. Passthrough ──

    /** 单轮直通：Actor → Synthesizer，无评估无反思，等同原 RespondStage */
    public static ReflectionTopology passthrough() {
        return ReflectionTopology.builder()
                .name("passthrough")
                .actor("reAct")
                .synthesizer("lastOutput")
                .edge("actor-0", "synthesizer-0")
                .entryNode("actor-0")
                .exitNode("synthesizer-0")
                .maxIterations(1)
                .build();
    }

    // ── 2. Reflexion ──

    /**
     * 经典反思模式：Actor → Evaluator → Router → [RETRY] Reflector → Actor → ...
     *
     * <p>流程：生成 → 评估 → 不满则反思后重新生成，直到通过或达上限。
     * 参考：Shinn et al., "Reflexion: Language Agents with Verbal Reinforcement Learning" (2023)
     */
    public static ReflectionTopology reflexion() {
        return ReflectionTopology.builder()
                .name("reflexion")
                .actor("reAct")
                .evaluator("heuristic")
                .router("threshold", Map.of("threshold", 0.7))
                .reflector("verbal")
                .synthesizer("lastOutput")
                .edge("actor-0", "evaluator-0")
                .edge("evaluator-0", "router-0")
                .edge("router-0", "reflector-0", EdgeCondition.ON_RETRY)
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_FALLBACK)
                .edge("reflector-0", "actor-0")  // 反思结果注入 Actor
                .entryNode("actor-0")
                .exitNode("synthesizer-0")
                .maxIterations(3)
                .build();
    }

    // ── 3. Self-Refine ──

    /**
     * 自我精炼模式：Actor → Evaluator → Router → [RETRY] Actor → ...
     *
     * <p>无独立 Reflector，Actor 自己根据评估反馈修正输出。
     * 参考：Madaan et al., "Self-Refine: Iterative Refinement with Self-Feedback" (2023)
     */
    public static ReflectionTopology selfRefine() {
        return ReflectionTopology.builder()
                .name("self-refine")
                .actor("reAct")
                .evaluator("heuristic")
                .router("fixedIter", Map.of("fixedIterations", 3))
                .synthesizer("lastOutput")
                .edge("actor-0", "evaluator-0")
                .edge("evaluator-0", "router-0")
                .edge("router-0", "actor-0", EdgeCondition.ON_RETRY)  // 回到 Actor 自我修正
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_FALLBACK)
                .entryNode("actor-0")
                .exitNode("synthesizer-0")
                .maxIterations(4)
                .build();
    }

    // ── 4. CRITIC ──

    /**
     * CRITIC 模式：Actor → ToolVerifier → Router → [RETRY] Reflector → Actor → ...
     *
     * <p>通过工具执行结果验证（退出码/测试套件/输出对比）来驱动反思。
     * 参考：Gou et al., "CRITIC: Large Language Models Can Self-Correct with
     * Tool-Interactive Critiquing" (2024)
     */
    public static ReflectionTopology critic() {
        return ReflectionTopology.builder()
                .name("critic")
                .actor("reAct")
                .evaluator("toolVerifierExitCode")
                .router("threshold", Map.of("threshold", 0.5))
                .reflector("verbal")
                .synthesizer("lastOutput")
                .edge("actor-0", "evaluator-0")
                .edge("evaluator-0", "router-0")
                .edge("router-0", "reflector-0", EdgeCondition.ON_RETRY)
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_FALLBACK)
                .edge("reflector-0", "actor-0")
                .entryNode("actor-0")
                .exitNode("synthesizer-0")
                .maxIterations(3)
                .build();
    }

    // ── 5. Multi-Evaluator ──

    /**
     * 多维度评估模式：Actor → HeuristicEvaluator → LLMJudge → Router → ...
     *
     * <p>同时用启发式规则和 LLM 多维度评估，Router 综合两者结论做决策。
     * 适用于对输出质量要求极高的场景（如代码审查、安全审计）。
     */
    public static ReflectionTopology multiEvaluator() {
        return ReflectionTopology.builder()
                .name("multi-evaluator")
                .actor("reAct")
                .evaluator("heuristic")
                .evaluator("llmJudge")
                .router("threshold")
                .reflector("verbal")
                .synthesizer("bestScore")  // 选取最高分轮次
                .edge("actor-0", "evaluator-0")
                .edge("evaluator-0", "evaluator-1")
                .edge("evaluator-1", "router-0")
                .edge("router-0", "reflector-0", EdgeCondition.ON_RETRY)
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_FALLBACK)
                .edge("reflector-0", "actor-0")
                .entryNode("actor-0")
                .exitNode("synthesizer-0")
                .maxIterations(3)
                .build();
    }

    // ── 6. Memory-Augmented ──

    /**
     * 记忆增强反思模式：Actor → Evaluator → Router → [RETRY] Memory检索 →
     * Reflector → Actor（注入历史经验）。
     *
     * <p>在反思之前先从记忆库中检索相似任务的成功经验，作为 Reflector 的参考输入。
     */
    public static ReflectionTopology memoryAugmented() {
        return ReflectionTopology.builder()
                .name("memory-augmented")
                .actor("reAct")
                .evaluator("heuristic")
                .router("threshold")
                .memory("inMemory")
                .reflector("verbal")
                .synthesizer("lastOutput")
                .edge("actor-0", "evaluator-0")
                .edge("evaluator-0", "router-0")
                .edge("router-0", "memory-0", EdgeCondition.ON_RETRY)
                .edge("memory-0", "reflector-0")
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_STOP)
                .edge("router-0", "synthesizer-0", EdgeCondition.ON_FALLBACK)
                .edge("reflector-0", "actor-0")
                .entryNode("actor-0")
                .exitNode("synthesizer-0")
                .maxIterations(3)
                .build();
    }

    // ── 工具方法 ──

    /** 按名称获取预置拓扑（找不到返回 null） */
    public static ReflectionTopology byName(String name) {
        return switch (name) {
            case "passthrough" -> passthrough();
            case "reflexion" -> reflexion();
            case "self-refine" -> selfRefine();
            case "critic" -> critic();
            case "multi-evaluator" -> multiEvaluator();
            case "memory-augmented" -> memoryAugmented();
            default -> null;
        };
    }
}
