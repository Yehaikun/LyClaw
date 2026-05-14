package lyjew.com.lyclaw.autoconfigure.facade;

import java.util.List;

/**
 * 延迟注册器接口——将扩展组件的"发现"和"注册"分离为两个阶段。
 *
 * <p>实现此接口的处理器在 BeanPostProcessor 阶段收集候选者但不注册，
 * 待 ExtensionWiring 在全部单例就绪后统一调用过滤链，通过的候选者
 * 回调 {@link #applyFiltered(List)} 完成注册。
 *
 * <p>设计意图：ExtensionFacade 的批量过滤模型需要所有候选者先集中，
 * 而传统 BeanPostProcessor 是逐 bean 处理的。此接口在不破坏 BPP 模型
 * 的前提下引入收集-过滤-注册三阶段，且对 PipelineStage、Interceptor
 * 等其他扩展类型同样适用。
 *
 * @param <T> 候选者类型，通常为原始 Spring Bean（{@code Object}）
 * @see ExtensionFacade
 * @see ExtensionWiring
 */
public interface DeferredRegistrar<T> {

    /** 扩展类别标签，用于日志汇总，如 "tool"、"stage"、"interceptor" */
    String category();

    /** 返回在 BeanPostProcessor 阶段收集的所有待过滤候选者 */
    List<T> getPending();

    /** 过滤完成后回调，仅包含通过所有过滤器的候选者，在此执行注册 */
    void applyFiltered(List<T> accepted);
}
