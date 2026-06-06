package lyjew.com.lyclaw.mesh;

import reactor.core.publisher.Flux;

/**
 * 编排引擎 SPI —— 定义多 Agent 协作的执行策略。
 *
 * <p>此接口是框架提供给用户的<strong>核心扩展点</strong>。
 * 用户可以实现此接口添加自定义编排模式，或覆盖内置模式的执行逻辑。</p>
 *
 * <p>框架内置实现 {@link lyjew.com.lyclaw.mesh.impl.DefaultOrchestrationEngine}
 * 提供 6 种标准模式。用户可以通过 {@link OrchestrationSpec#getPattern()} 选择模式。</p>
 *
 * <h3>用户自定义编排示例</h3>
 * <pre>{@code
 * public class CustomEngine implements OrchestrationEngine {
 *     &#64;Override
 *     public String engineName() { return "my-custom"; }
 *
 *     &#64;Override
 *     public OrchestrationResult execute(OrchestrationSpec spec) {
 *         // 自定义编排逻辑
 *     }
 * }
 * }</pre>
 *
 * <h3>模式扩展</h3>
 * 用户可以通过实现 {@link OrchestrationEngine} 接口来：
 * <ul>
 *   <li>添加新的编排模式（如 TOURNAMENT、AUCTION）</li>
 *   <li>覆盖内置模式的执行逻辑</li>
 *   <li>添加拦截器（前置检查、后置处理）</li>
 *   <li>自定义结果聚合方式</li>
 * </ul>
 */
public interface OrchestrationEngine {

    /** 引擎名称 */
    String engineName();

    /**
     * 执行编排，返回聚合结果。
     *
     * @param spec 编排规格（模式、任务、参与Agent、参数等）
     * @return 编排结果（聚合文本 + 各Agent单独结果）
     */
    OrchestrationResult execute(OrchestrationSpec spec);

    /**
     * 流式版编排 —— 以 Flux 形式返回执行过程中的事件。
     *
     * <p>对于流式场景（如 DEBATE 模式的逐轮结果、FAN_OUT 的逐个结果），
     * 用户可以通过此 API 获取实时进度。</p>
     *
     * @param spec 编排规格
     * @return 事件流（每项为一个编排事件）
     */
    default Flux<OrchestrationEvent> executeStream(OrchestrationSpec spec) {
        return Flux.defer(() -> {
            long start = System.currentTimeMillis();
            return Flux.fromArray(new OrchestrationEvent[]{
                    OrchestrationEvent.started(spec),
                    OrchestrationEvent.completed(execute(spec), System.currentTimeMillis() - start)
            });
        });
    }

    /** 此引擎支持的编排模式列表（空 = 全部支持） */
    default OrchestrationPattern[] supportedPatterns() {
        return OrchestrationPattern.values();
    }

    /** 是否支持指定模式 */
    default boolean supports(OrchestrationPattern pattern) {
        for (OrchestrationPattern p : supportedPatterns()) {
            if (p == pattern) return true;
        }
        return false;
    }
}
