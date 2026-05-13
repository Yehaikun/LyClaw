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

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            if (bean instanceof ReactivePipelineStage reactiveStage) {
                discoveredReactiveStages.add(reactiveStage);
                Class<?> clazz = bean.getClass();

                // 解析 @PipelineStage 注解中的顺序约束
                //查找改代理的bean内是否有PipelineStage注解，有则注解赋值给stageAnn，否则为null
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
                log.info("正在注册 pipeline stage: {} (order={})",
                        reactiveStage.getStageName(), reactiveStage.getOrder());
            }
        } catch (Exception e) {
            log.error("Failed to process @PipelineStage bean '{}': {}", beanName, e.getMessage());
        }
        return bean;
    }
    @Override
    public void afterSingletonsInstantiated() {
        log.info("============================================");
        log.info("  PipelineStageProcessor 管道阶段扫描完成");
        log.info("  共发现 {} 个阶段", discoveredReactiveStages.size());
        for (int i = 0; i < discoveredReactiveStages.size(); i++) {
            ReactivePipelineStage s = discoveredReactiveStages.get(i);
            log.info("    {}. [{}] order={}", i + 1, s.getStageName(), s.getOrder());
        }
        log.info("============================================");
    }
    /** @return 按声明顺序排序后的阶段列表 */
    public List<ReactivePipelineStage> getSortedStages() {
        return TopologySort.sort(
                new ArrayList<>(discoveredReactiveStages),
                stage -> {
                    // dependencyResolver: 返回 stage 的所有依赖项（必须在它之前执行的 stage）
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
        );
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
     * 动态代理拿到注解的属性的
     * @param ann
     * @param attr
     * @param defaultValue
     * @return
     * @param <T>
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
