package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.interceptor.Interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.*;

/**
 * {@link BeanPostProcessor} that discovers beans implementing
 * {@link Interceptor} and optionally decorated with the
 * {@code @Interceptor} annotation for ordering constraints.
 */
public class InterceptorProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(InterceptorProcessor.class);

    private final List<Interceptor> discoveredInterceptors = new ArrayList<>();
    private final Map<String, Class<?>[]> afterConstraints = new LinkedHashMap<>();
    private final Map<String, Class<?>[]> beforeConstraints = new LinkedHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            if (bean instanceof Interceptor interceptor) {
                discoveredInterceptors.add(interceptor);
                Class<?> clazz = bean.getClass();

                Object interceptorAnn = findAnnotation(clazz, "Interceptor");
                if (interceptorAnn != null) {
                    String name = getAttr(interceptorAnn, "name", "");
                    Class<?>[] after = getAttr(interceptorAnn, "after", new Class<?>[0]);
                    Class<?>[] before = getAttr(interceptorAnn, "before", new Class<?>[0]);
                    String key = (name != null && !name.isEmpty()) ? name : clazz.getSimpleName();
                    if (after != null && after.length > 0) {
                        afterConstraints.put(key, after);
                    }
                    if (before != null && before.length > 0) {
                        beforeConstraints.put(key, before);
                    }
                }
                log.debug("Discovered interceptor: {}", interceptor.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.error("Failed to process @Interceptor bean '{}': {}", beanName, e.getMessage());
        }
        return bean;
    }

    /**
     * Returns discovered interceptors sorted by their declared order.
     */
    public List<Interceptor> getSortedInterceptors() {
        List<Interceptor> sorted = new ArrayList<>(discoveredInterceptors);
        sorted.sort(Comparator.comparingInt(Interceptor::getOrder));
        return sorted;
    }

    public List<Interceptor> getDiscoveredInterceptors() {
        return Collections.unmodifiableList(discoveredInterceptors);
    }

    public int getInterceptorCount() {
        return discoveredInterceptors.size();
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
