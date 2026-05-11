package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.pipeline.PipelineStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.*;

/**
 * {@link BeanPostProcessor} that discovers beans implementing
 * {@link PipelineStage} and optionally decorated with the
 * {@code @PipelineStage} annotation for ordering constraints.
 */
public class PipelineStageProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PipelineStageProcessor.class);

    private final List<PipelineStage> discoveredStages = new ArrayList<>();
    private final Map<String, Class<?>[]> afterConstraints = new LinkedHashMap<>();
    private final Map<String, Class<?>[]> beforeConstraints = new LinkedHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            if (bean instanceof PipelineStage stage) {
                discoveredStages.add(stage);
                Class<?> clazz = bean.getClass();

                Object stageAnn = findAnnotation(clazz, "PipelineStage");
                if (stageAnn != null) {
                    String name = getAttr(stageAnn, "name", "");
                    Class<?>[] after = getAttr(stageAnn, "after", new Class<?>[0]);
                    Class<?>[] before = getAttr(stageAnn, "before", new Class<?>[0]);
                    String key = (name != null && !name.isEmpty()) ? name : clazz.getSimpleName();
                    if (after != null && after.length > 0) {
                        afterConstraints.put(key, after);
                    }
                    if (before != null && before.length > 0) {
                        beforeConstraints.put(key, before);
                    }
                }
                log.debug("Discovered pipeline stage: {} (order={})", stage.getStageName(), stage.getOrder());
            }
        } catch (Exception e) {
            log.error("Failed to process @PipelineStage bean '{}': {}", beanName, e.getMessage());
        }
        return bean;
    }

    /**
     * Returns discovered stages sorted by their declared order.
     * Full topological-sort respecting {@code after}/{@code before} constraints
     * will be implemented in Phase 2.
     */
    public List<PipelineStage> getSortedStages() {
        if (afterConstraints.isEmpty() && beforeConstraints.isEmpty()) {
            List<PipelineStage> sorted = new ArrayList<>(discoveredStages);
            sorted.sort(Comparator.comparingInt(PipelineStage::getOrder));
            return sorted;
        }
        // When constraints exist, fall back to order-based sort for now
        List<PipelineStage> sorted = new ArrayList<>(discoveredStages);
        sorted.sort(Comparator.comparingInt(PipelineStage::getOrder));
        return sorted;
    }

    public List<PipelineStage> getDiscoveredStages() {
        return Collections.unmodifiableList(discoveredStages);
    }

    public int getStageCount() {
        return discoveredStages.size();
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

    @SuppressWarnings("unchecked")
    private <T> T getAttr(Object ann, String attr, T defaultValue) {
        try {
            return (T) ann.getClass().getMethod(attr).invoke(ann);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
