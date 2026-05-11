package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.adapter.ModelAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link BeanPostProcessor} that discovers beans implementing
 * {@link ModelAdapter} and optionally decorated with the
 * {@code @Adapter} annotation to bind a provider key.
 *
 * @deprecated 已由 ChatModelPostProcessor 取代。
 *             新系统使用 @ChatModel 注解标记 ChatModel 实现。
 */
@Deprecated
public class AdapterAnnotationProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AdapterAnnotationProcessor.class);

    private final Map<String, ModelAdapter> providerMap = new ConcurrentHashMap<>();
    private final List<ModelAdapter> allAdapters = new ArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            if (bean instanceof ModelAdapter adapter) {
                allAdapters.add(adapter);
                Class<?> clazz = bean.getClass();

                Object adapterAnn = findAnnotation(clazz, "Adapter");
                if (adapterAnn != null) {
                    String provider = getAttr(adapterAnn, "provider", "");
                    if (provider != null && !provider.isEmpty()) {
                        providerMap.put(provider, adapter);
                        log.info("Registered adapter for provider '{}': {}", provider, clazz.getSimpleName());
                    }
                } else {
                    // Fallback: use adapter.getProvider()
                    String provider = adapter.getProvider();
                    if (provider != null && !provider.isEmpty()) {
                        providerMap.putIfAbsent(provider, adapter);
                        log.debug("Discovered adapter for provider '{}' (no @Adapter annotation): {}",
                                provider, clazz.getSimpleName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to process @Adapter bean '{}': {}", beanName, e.getMessage());
        }
        return bean;
    }

    public Optional<ModelAdapter> getAdapter(String provider) {
        return Optional.ofNullable(providerMap.get(provider));
    }

    public List<ModelAdapter> getAllAdapters() {
        return Collections.unmodifiableList(allAdapters);
    }

    public Set<String> getAvailableProviders() {
        return Collections.unmodifiableSet(providerMap.keySet());
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
