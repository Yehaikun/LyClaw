package lyjew.com.lyclaw.autoconfigure.facade;

/**
 * Configuration properties for the extension registration pipeline.
 * Bound under the {@code lyclaw.extension} prefix.
 */
public class ExtensionProperties {

    private boolean filteringEnabled = true;
    private String orderingStrategy = "topology"; // topology | numeric
    private boolean failFast = false;

    public boolean isFilteringEnabled() {
        return filteringEnabled;
    }

    public void setFilteringEnabled(boolean filteringEnabled) {
        this.filteringEnabled = filteringEnabled;
    }

    public String getOrderingStrategy() {
        return orderingStrategy;
    }

    public void setOrderingStrategy(String orderingStrategy) {
        this.orderingStrategy = orderingStrategy;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }
}
