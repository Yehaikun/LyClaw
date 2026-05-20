package lyjew.com.lyclaw.chat.config;

import java.util.Objects;

/**
 * Immutable value object holding a provider name and model name pair.
 *
 * <p>Serves as the canonical identifier for a model throughout the resolution
 * system. The canonical string form is {@code "provider/model"}, which is used
 * in configuration, fallback chains, alias maps, and run-metadata overrides.
 *
 * <p>Instances are created via the constructor or parsed from a canonical
 * string with {@link #parse(String)}. This class is thread-safe and suitable
 * for use as a map key.
 *
 * <h3>Examples</h3>
 * <pre>{@code
 *   ModelRef ref = new ModelRef("openai", "gpt-4o");
 *   ref.toCanonicalId();          // "openai/gpt-4o"
 *
 *   ModelRef parsed = ModelRef.parse("deepseek/deepseek-v4-flash");
 *   parsed.getProvider();         // "deepseek"
 *   parsed.getModel();            // "deepseek-v4-flash"
 * }</pre>
 */
public final class ModelRef {

    private final String provider;
    private final String model;

    /**
     * Creates a new ModelRef with the given provider and model name.
     *
     * @param provider the model provider name (e.g. "openai", "deepseek"),
     *                 must not be null or blank
     * @param model    the model name (e.g. "gpt-4o", "deepseek-v4-flash"),
     *                 must not be null or blank
     * @throws IllegalArgumentException if provider or model is null or blank
     */
    public ModelRef(String provider, String model) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be null or blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be null or blank");
        }
        this.provider = provider.trim();
        this.model = model.trim();
    }

    /** Returns the provider name (e.g. "openai"). */
    public String getProvider() {
        return provider;
    }

    /** Returns the model name (e.g. "gpt-4o"). */
    public String getModel() {
        return model;
    }

    /**
     * Returns the canonical identifier string in the form {@code "provider/model"}.
     *
     * @return canonical id, e.g. {@code "openai/gpt-4o"}
     */
    public String toCanonicalId() {
        return provider + "/" + model;
    }

    /**
     * Parses a canonical identifier string into a {@link ModelRef}.
     *
     * <p>The input must contain exactly one {@code '/'} character separating
     * the provider name from the model name. Neither part may be blank after
     * trimming. Strings with multiple slashes are rejected as malformed.
     *
     * @param canonicalId the string to parse, e.g. {@code "openai/gpt-4o"}
     * @return a new {@code ModelRef}, or {@code null} if the input is null,
     *         blank, or does not match the {@code "provider/model"} format
     */
    public static ModelRef parse(String canonicalId) {
        if (canonicalId == null || canonicalId.isBlank()) {
            return null;
        }
        String trimmed = canonicalId.trim();
        int slashIdx = trimmed.indexOf('/');
        if (slashIdx <= 0 || slashIdx >= trimmed.length() - 1) {
            return null;
        }
        String provider = trimmed.substring(0, slashIdx).trim();
        String model = trimmed.substring(slashIdx + 1).trim();
        if (provider.isEmpty() || model.isEmpty()) {
            return null;
        }
        // Reject multiple slashes (e.g. "a/b/c")
        if (model.indexOf('/') >= 0) {
            return null;
        }
        return new ModelRef(provider, model);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelRef other)) return false;
        return provider.equals(other.provider) && model.equals(other.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, model);
    }

    @Override
    public String toString() {
        return "ModelRef{provider='" + provider + "', model='" + model + "'}";
    }
}
