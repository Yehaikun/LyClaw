package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.autoconfigure.ordering.TopologySort;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.*;
/**
 * {@link BeanPostProcessor} 实现，自动发现实现了 {@link ReactivePipelineStage} 接口
 * 的 Spring Bean，并解析 {@code @PipelineStage} 注解的排序约束（after/before）。
 *
 * <p>对每个发现的响应式阶段 Bean，提取其注解中的 name、after、before 约束信息，
 * 供后续拓扑排序使用。</p>
 */
public class PipelineStageProcessor implements BeanPostProcessor, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PipelineStageProcessor.class);

    private final List<ReactivePipelineStage> discoveredReactiveStages = new ArrayList<>();
    private final Map<String, Class<?>[]> afterConstraints = new LinkedHashMap<>();
    private final Map<String, Class<?>[]> beforeConstraints = new LinkedHashMap<>();

    /**
     * Spring Bean 后处理器核心方法，在 Bean 初始化完成后被容器调用，负责发现和收集
     * 管道阶段组件并解析其排序约束信息。
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li><b>类型检测：</b>通过 {@code instanceof ReactivePipelineStage} 检查当前
     *       Bean 是否实现了 ReactivePipelineStage 接口，未实现则跳过。这种设计使得
     *       本处理器可以与其他 BeanPostProcessor 共存而不会相互干扰。</li>
     *   <li><b>阶段收集：</b>将发现的阶段实例加入 {@code discoveredReactiveStages} 列表，
     *       供后续的拓扑排序和对外查询使用。</li>
     *   <li><b>注解约束解析：</b>通过反射在 Bean 类上查找 {@code @PipelineStage} 注解。
     *       如果注解存在，提取 name（阶段名称）、after（必须在哪些阶段之后执行）、
     *       before（必须在哪些阶段之前执行）三个约束属性。这些约束信息分别存入
     *       {@code afterConstraints} 和 {@code beforeConstraints} Map 中，
     *       以阶段名称或类名（首字母小写）作为 key。</li>
     *   <li><b>日志记录：</b>为每个发现的阶段输出 INFO 级别的注册日志，包含阶段名称
     *       和执行顺序（order），方便运维人员追踪管道的组件构成。</li>
     * </ol>
     *
     * <p><b>异常处理：</b>整个处理过程包裹在 try-catch 块中，任何步骤的异常都被
     * 捕获并记录 ERROR 日志，不会中断 Spring 容器启动流程。reactiveStage 变量使用
     * Java 16 的 instanceof 模式匹配语法，同时完成类型检查和变量绑定。</p>
     *
     * @param bean Spring 容器中已初始化的 Bean 实例，可能实现了 ReactivePipelineStage 接口
     * @param beanName Bean 在 Spring 容器中的注册名称，用于日志记录和问题定位
     * @return 始终返回原始 bean 实例（不修改 bean 对象引用）
     * @throws BeansException 当 Bean 后处理过程中发生严重错误时抛出，但内部已捕获异常通常不会传播
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            if (bean instanceof ReactivePipelineStage reactiveStage) {
                discoveredReactiveStages.add(reactiveStage);
                Class<?> clazz = bean.getClass();

                // 解析 @PipelineStage 注解中的顺序约束
                // 查找该代理的 Bean 内是否有 PipelineStage 注解，有则将注解对象赋值给 stageAnn，否则为 null
                Object stageAnn = findAnnotation(clazz, "PipelineStage");
                if (stageAnn != null) {
                    String name = getAttr(stageAnn, "name", "");
                    Class<?>[] after = getAttr(stageAnn, "after", new Class<?>[0]);
                    Class<?>[] before = getAttr(stageAnn, "before", new Class<?>[0]);
                    // 默认类名首字母小写
                    String key = (name != null && !name.isEmpty()) ? name : clazz.getSimpleName();
                    if (after != null && after.length > 0) {
                        afterConstraints.put(key, after);
                    }
                    if (before != null && before.length > 0) {
                        beforeConstraints.put(key, before);
                    }
                }
                log.info("注册管线阶段: {} (order={})",
                        reactiveStage.getStageName(), reactiveStage.getOrder());
            }
        } catch (Exception e) {
            log.error("[FAIL] 处理 @PipelineStage Bean '{}' 失败: {}", beanName, e.getMessage());
        }
        return bean;
    }
    /**
     * SmartInitializingSingleton 回调方法，在所有单例 Bean 完成实例化和初始化后由
     * Spring 容器调用，输出管道阶段扫描的汇总摘要日志。
     *
     * <p><b>执行时机：</b>此方法在 Spring 容器启动的最后阶段调用，此时所有的 Bean
     * （包括延迟初始化的 Bean）都已完成实例化、属性注入和初始化回调。这是输出完整
     * 管道清单的理想时机——在此之后不会有新的管道阶段被发现。</p>
     *
     * <p><b>日志输出格式：</b>使用醒目的分隔线（等号组成的视觉边框）包裹管道清单，
     * 每行输出阶段序号、阶段名称（getName()）和执行顺序（getOrder()），形成清晰的
     * 编号列表。示例输出：</p>
     * <pre>{@code
     * ============================================
     *   PipelineStageProcessor 管道阶段扫描完成
     *   共发现 5 个阶段
     *     1. [前置拦截器] order=100
     *     2. [安全校验] order=200
     *     3. [LLM调用] order=300
     *     4. [后置拦截器] order=400
     *     5. [日志记录] order=500
     * ============================================
     * }</pre>
     *
     * <p>这些日志在应用启动时输出，帮助运维人员和开发者快速确认：管道中包含哪些阶段、
     * 阶段数量是否符合预期、是否存在阶段缺失或重复等问题。</p>
     */
    @Override
    public void afterSingletonsInstantiated() {
        log.info("════════════════════════════════════════════");
        log.info("  PipelineStageProcessor 管线阶段扫描完成");
        log.info("  共发现 {} 个阶段", discoveredReactiveStages.size());
        for (int i = 0; i < discoveredReactiveStages.size(); i++) {
            ReactivePipelineStage s = discoveredReactiveStages.get(i);
            log.info("    {}. [{}] order={}", i + 1, s.getStageName(), s.getOrder());
        }
        log.info("════════════════════════════════════════════");
    }
    /** @return 按声明顺序排序后的阶段列表 */
    public List<ReactivePipelineStage> getSortedStages() {
        try {
            return TopologySort.sort(
                    new ArrayList<>(discoveredReactiveStages),
                    this::resolveDependencies);
        } catch (IllegalStateException e) {
            log.warn("拓扑排序失败（循环依赖），回退到 getOrder() 数值排序: {}", e.getMessage());
            List<ReactivePipelineStage> fallback = new ArrayList<>(discoveredReactiveStages);
            fallback.sort(Comparator.comparingInt(ReactivePipelineStage::getOrder));
            return fallback;
        }
    }

    /** 解析 stage 的依赖项（after/before 约束）。 */
    private List<ReactivePipelineStage> resolveDependencies(ReactivePipelineStage stage) {
        List<ReactivePipelineStage> deps = new ArrayList<>();
        String key = stageName(stage);

        // 1) after 约束：stage 声明了 after=X → X 必须在 stage 之前
        Class<?>[] after = afterConstraints.get(key);
        if (after != null) {
            for (Class<?> c : after) {
                ReactivePipelineStage dep = findByClass(c);
                if (dep != null) deps.add(dep);
            }
        }

        // 2) before 约束的反向：如果另一个 stage Y 声明 before=X（X=当前stage），
        //    说明 Y 必须在当前 stage 之前执行 → Y 是 dependency
        for (var entry : beforeConstraints.entrySet()) {
            for (Class<?> c : entry.getValue()) {
                if (c.isAssignableFrom(stage.getClass())) {
                    ReactivePipelineStage dep = findByName(entry.getKey());
                    if (dep != null) deps.add(dep);
                }
            }
        }
        return deps;
    }




    // 辅助方法：按 Class 从 discoveredReactiveStages 查找实例
    private ReactivePipelineStage findByClass(Class<?> clazz) {
        return discoveredReactiveStages.stream()
                .filter(s -> clazz.isAssignableFrom(s.getClass()))
                .findFirst().orElse(null);
    }

    // 辅助方法：按名称查找
    private ReactivePipelineStage findByName(String name) {
        return discoveredReactiveStages.stream()
                .filter(s -> s.getStageName().equals(name)
                        || s.getClass().getSimpleName().equals(name))
                .findFirst().orElse(null);
    }

    private String stageName(ReactivePipelineStage stage) {
        Object stageAnn = findAnnotation(stage.getClass(), "PipelineStage");
        if (stageAnn != null) {
            String name = getAttr(stageAnn, "name", "");
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return stage.getClass().getSimpleName();
    }

    /** @return 只读的阶段列表 */
    public List<ReactivePipelineStage> getDiscoveredReactiveStages() {
        return Collections.unmodifiableList(discoveredReactiveStages);
    }

    /** @return 响应式阶段数量 */
    public int getReactiveStageCount() {
        return discoveredReactiveStages.size();
    }

    // --- reflection helpers ----------------------------------------------------

    private Object findAnnotation(Class<?> clazz, String simpleName) {
        for (var a : clazz.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals(simpleName)) {
                return a;
            }
        }
        return null;
    }

    /**
     * 通过 Java 反射动态获取注解对象的指定属性值，如果获取失败则返回默认值。
     *
     * <p>该方法利用 {@code ann.getClass().getMethod(attr).invoke(ann)} 在运行时动态调用
     * 注解接口中定义的属性方法（如 name()、after()、before() 等），实现对注解属性值的
     * 零编译期依赖读取。如果注解类中不存在指定的属性方法，或者反射调用过程中发生任何
     * 异常（如访问权限受限、类型转换失败等），方法不会抛出异常，而是返回调用者提供的
     * 默认值 defaultValue，确保处理流程的健壮性。</p>
     *
     * @param ann 注解对象实例，通常由 {@link #findAnnotation(Class, String)} 方法返回
     * @param attr 要获取的注解属性方法名称，例如 "name"、"after"、"before"
     * @param defaultValue 当反射调用失败时返回的默认值，调用者根据业务需要提供合适的回退值
     * @param <T> 泛型类型参数，代表属性值的实际类型，由调用者通过 defaultValue 的类型推断
     * @return 注解属性的实际值（反射调用成功时）或 defaultValue（反射调用失败时）
     */
    @SuppressWarnings("unchecked")
    private <T> T getAttr(Object ann, String attr, T defaultValue) {
        try {
            return (T) ann.getClass().getMethod(attr).invoke(ann);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
