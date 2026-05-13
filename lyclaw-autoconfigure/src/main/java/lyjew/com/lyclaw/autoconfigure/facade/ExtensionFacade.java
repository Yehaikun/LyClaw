package lyjew.com.lyclaw.autoconfigure.facade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * 扩展注册编排门面，负责将发现的扩展候选者通过可配置的过滤器链进行处理，
 * 按分类对候选者进行"接受"或"跳过"的决策。
 *
 * <p><b>设计理念：</b>在 LyClaw 的插件式架构中，各种扩展组件（工具、管道阶段、
 * 拦截器、存储后端、聊天模型等）通过注解自动发现后，并非所有候选者都应该被注册。
 * ExtensionFacade 提供了一个统一的编排管道，将多个过滤条件串联执行，只有通过所有
 * 过滤器检查的候选者才会被"接受"并进入后续的注册流程。</p>
 *
 * <p><b>过滤器链机制：</b>通过 {@link #addFilter(Predicate)} 方法可以注册任意数量的
 * {@link java.util.function.Predicate} 过滤器。在 {@link #process(List, String)} 方法中，
 * 每个候选者依次通过所有过滤器的 {@code test()} 方法检测，任意一个过滤器返回 false
 * 即导致该候选者被跳过（短路逻辑）。被跳过的候选者的简单类名会被记录在 skipped 列表中，
 * 并在处理完成后以汇总日志的形式输出（INFO 级别）。</p>
 *
 * <p><b>策略控制：</b></p>
 * <ul>
 *   <li><b>filteringEnabled（过滤开关）：</b>通过 {@link #filteringEnabled(boolean)} 设置，
 *       默认为 true。当设置为 false 时，所有候选者都直接通过，不做任何过滤。
 *       适用于调试场景或需要完全禁用条件过滤的场合。</li>
 *   <li><b>failFast（快速失败）：</b>通过 {@link #failFast(boolean)} 设置，默认为 false。
 *       当设置为 true 时，如果处理过程中发生异常，会立即抛出 {@link RuntimeException}
 *       终止整个处理流程，而不是跳过该候选者继续处理下一个。适用于对扩展完整性有严格
 *       要求的场景。</li>
 * </ul>
 *
 * <p><b>日志输出：</b>处理完成后输出汇总日志，格式为 "[ExtensionFacade] 分类: accepted
 * {接受数}/{总数}, skipped: {跳过的类名列表}"，方便运维人员快速了解哪些扩展被注册、
 * 哪些被过滤以及过滤原因。</p>
 *
 * @param <T> 候选者的具体类型，可以是 Tool、ReactivePipelineStage、Interceptor 等扩展接口类型
 * @see ConditionFilter 提供基于 @ToolCondition 注解的条件过滤断言
 */
public class ExtensionFacade {

    private static final Logger log = LoggerFactory.getLogger(ExtensionFacade.class);

    private final List<Predicate<Object>> filters = new ArrayList<>();
    private boolean filteringEnabled = true;
    private boolean failFast = false;

    public ExtensionFacade filteringEnabled(boolean enabled) {
        this.filteringEnabled = enabled;
        return this;
    }

    public ExtensionFacade failFast(boolean failFast) {
        this.failFast = failFast;
        return this;
    }

    public ExtensionFacade addFilter(Predicate<Object> filter) {
        filters.add(filter);
        return this;
    }

    /**
     * Process candidates through the pipeline: filter, validate, return accepted.
     *
     * @param candidates the discovered extensions to evaluate
     * @param category   label for logging (e.g. "tool", "stage", "interceptor")
     * @return the list of accepted (passing all filters) extensions
     */
    public <T> List<T> process(List<T> candidates, String category) {
        List<T> accepted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (T candidate : candidates) {
            try {
                if (filteringEnabled) {
                    boolean rejected = false;
                    for (Predicate<Object> filter : filters) {
                        if (!filter.test(candidate)) {
                            rejected = true;
                            skipped.add(candidate.getClass().getSimpleName());
                            break;
                        }
                    }
                    if (rejected) {
                        continue;
                    }
                }
                accepted.add(candidate);
            } catch (Exception e) {
                log.error("[ExtensionFacade] Failed to process {} extension: {}", category, e.getMessage());
                if (failFast) {
                    throw new RuntimeException("Extension processing failed for " + category, e);
                }
            }
        }

        log.info("[ExtensionFacade] {}: accepted {}/{}, skipped: {}",
                category, accepted.size(), candidates.size(),
                skipped.isEmpty() ? "none" : String.join(", ", skipped));
        return accepted;
    }
}
