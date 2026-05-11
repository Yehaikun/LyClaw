package lyjew.com.lyclaw.autoconfigure.facade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Creates {@link Predicate} filters driven by the {@code @ToolCondition}
 * annotation, binding conditional activation rules to the Spring
 * {@link Environment}.
 */
public class ConditionFilter {

    private static final Logger log = LoggerFactory.getLogger(ConditionFilter.class);
    private final Environment env;

    public ConditionFilter(Environment env) {
        this.env = env;
    }

    /**
     * Creates a {@link Predicate} that filters candidates based on
     * {@code @ToolCondition} annotation attributes:
     * <ul>
     *   <li>{@code requiresConfig} — property must be present</li>
     *   <li>{@code requiresProfile} — profile must be active</li>
     *   <li>{@code requiresClass} — class must be on the classpath</li>
     * </ul>
     */
    public Predicate<Object> toolConditionFilter() {
        return candidate -> {
            Class<?> clazz = candidate.getClass();
            for (var ann : clazz.getAnnotations()) {
                if (!ann.annotationType().getSimpleName().equals("ToolCondition")) {
                    continue;
                }
                try {
                    String[] requiresConfig = (String[]) ann.getClass()
                            .getMethod("requiresConfig").invoke(ann);
                    String[] requiresProfile = (String[]) ann.getClass()
                            .getMethod("requiresProfile").invoke(ann);
                    Class<?>[] requiresClass = (Class<?>[]) ann.getClass()
                            .getMethod("requiresClass").invoke(ann);

                    for (String config : requiresConfig) {
                        if (env.getProperty(config) == null) {
                            log.info("[ConditionFilter] {} disabled: missing config '{}'",
                                    clazz.getSimpleName(), config);
                            return false;
                        }
                    }
                    for (String profile : requiresProfile) {
                        if (!Arrays.asList(env.getActiveProfiles()).contains(profile)) {
                            log.debug("[ConditionFilter] {} disabled: profile '{}' not active",
                                    clazz.getSimpleName(), profile);
                            return false;
                        }
                    }
                    for (Class<?> req : requiresClass) {
                        try {
                            Class.forName(req.getName());
                        } catch (ClassNotFoundException e) {
                            log.info("[ConditionFilter] {} disabled: class '{}' not on classpath",
                                    clazz.getSimpleName(), req.getName());
                            return false;
                        }
                    }
                } catch (Exception ignored) {
                    // reflective access failed — treat as not condition-constrained
                }
            }
            return true;
        };
    }
}
