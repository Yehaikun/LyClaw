package lyjew.com.lyclaw.autoconfigure.facade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * Orchestration pipeline that processes discovered extensions through a
 * configurable filter chain, accepting or skipping candidates per category.
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
