package lyjew.com.lyclaw.chat.config;

import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
import lyjew.com.lyclaw.chat.catalog.ModelCatalog;
import lyjew.com.lyclaw.chat.catalog.ModelCatalogEntry;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.RunMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central service for resolving which model to use for a given agent and session.
 *
 * <p>Implements a layered resolution strategy that walks a well-defined
 * precedence chain, from the most specific override down to the system-wide
 * fallback:
 *
 * <ol>
 *   <li>Run-metadata overrides set by a parent sub-agent spawner, checked in
 *       both the typed {@link RunMetadata} object and the legacy map-based
 *       {@code runMetadataMap} on {@link AgentContext}</li>
 *   <li>The {@link ChatRequest#getModel()} field, which may carry a
 *       {@code "provider/model"} canonical string set by {@code @Agent}
 *       annotation processing via {@code AgentInvocationHandler}</li>
 *   <li>Agent-config extensions stored under
 *       {@code ctx.getAttribute("agentExtensions")}, resolved by
 *       {@code AgentConfigResolver} with keys {@code "model"},
 *       {@code "provider"}, and {@code "fallback"}</li>
 *   <li>Global defaults derived from the configured fallback chain (the first
 *       entry is the primary default)</li>
 *   <li>Final fallback: the first available model in the
 *       {@link ChatModelRegistry}</li>
 * </ol>
 *
 * <p>Additionally maintains an alias-to-canonical-id map built from the
 * {@link ModelCatalog}, enabling short-name lookups such as {@code "gpt-4o"}
 * resolving to {@code "openai/gpt-4o"}.
 *
 * <p>Thread-safe. The alias map uses a {@link ConcurrentHashMap} and can be
 * refreshed at runtime via {@link #refreshAliasMap()}.
 *
 * @see ModelRef
 * @see AgentModelConfig
 */
public class ModelResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ModelResolutionService.class);

    // Run-metadata keys (map-based, backward compatible)
    private static final String MDK_RESOLVED_MODEL = "resolvedModel";
    private static final String MDK_RESOLVED_PROVIDER = "resolvedProvider";
    private static final String MDK_FALLBACK_CHAIN = "fallbackChain";

    // Attribute key for per-agent extensions map resolved by AgentConfigResolver
    private static final String ATTR_AGENT_EXTENSIONS = "agentExtensions";

    // Extension keys within the agentExtensions map
    private static final String EXT_MODEL = "model";
    private static final String EXT_PROVIDER = "provider";
    private static final String EXT_FALLBACK = "fallback";

    private final ChatModelRegistry registry;
    private final ModelCatalog catalog;
    private final List<String> defaultFallbackChain;

    /** Alias to canonical-id map, rebuilt from the catalog. */
    private final ConcurrentHashMap<String, String> aliasMap = new ConcurrentHashMap<>();

    /**
     * Constructs the resolution service.
     *
     * @param registry             the model registry for availability checks and
     *                             first-available fallback
     * @param catalog              the model catalog for alias resolution and
     *                             available-model enumeration
     * @param defaultFallbackChain ordered fallback chain of canonical ids
     *                             ({@code "provider/model"}), may be empty but
     *                             not null
     */
    public ModelResolutionService(ChatModelRegistry registry,
                                   ModelCatalog catalog,
                                   List<String> defaultFallbackChain) {
        this.registry = registry;
        this.catalog = catalog;
        this.defaultFallbackChain = List.copyOf(defaultFallbackChain);
        refreshAliasMap();
    }

    // ── Primary resolution ─────────────────────────────────────────────────

    /**
     * Resolves the effective primary chat model for the given agent context.
     *
     * <p>Walks the full precedence chain and returns a {@link ModelRef}
     * identifying the provider and model to use. Never returns null; the final
     * fallback is the first available model in the registry.
     *
     * @param ctx the agent context carrying request, metadata, and extensions
     * @return the resolved model reference (never null)
     */
    public ModelRef resolveEffectiveModel(AgentContext ctx) {
        // 1. Run-metadata overrides (sub-agent spawner)
        ModelRef rmRef = resolveFromRunMetadata(ctx);
        if (rmRef != null) {
            return rmRef;
        }

        // 2. ChatRequest.model (set by @Agent annotation via AgentInvocationHandler)
        ChatRequest request = ctx.getChatRequest();
        if (request != null) {
            ModelRef parsed = parseModelString(request.getModel());
            if (parsed != null) {
                log.debug("Resolved model from ChatRequest: {}", parsed.toCanonicalId());
                return parsed;
            }
        }

        // 3. Agent-config extensions (from AgentConfigResolver)
        ModelRef extRef = resolveFromAgentExtensions(ctx);
        if (extRef != null) {
            return extRef;
        }

        // 4. Global default (first entry of fallback chain)
        ModelRef globalDefault = firstFromFallbackChain();
        if (globalDefault != null) {
            log.debug("Resolved model from global default: {}", globalDefault.toCanonicalId());
            return globalDefault;
        }

        // 5. Final fallback: first available in registry
        ModelRef firstAvailable = resolveFirstAvailable();
        log.debug("Resolved model via first-available fallback: {}", firstAvailable.toCanonicalId());
        return firstAvailable;
    }

    /**
     * Resolves the image / vision model for the given agent context.
     *
     * <p>Checks run-metadata and agent-config extensions for an explicit image
     * model. If absent, falls back to the primary chat model resolved via
     * {@link #resolveEffectiveModel(AgentContext)}.
     *
     * @param ctx the agent context
     * @return the resolved image model reference (never null)
     */
    public ModelRef resolveImageModel(AgentContext ctx) {
        // Check typed RunMetadata for image model
        RunMetadata rm = ctx.getRunMetadata();
        if (rm != null) {
            String imageModel = rm.getImageModel();
            if (imageModel != null && !imageModel.isBlank()) {
                ModelRef parsed = ModelRef.parse(imageModel.trim());
                if (parsed != null) {
                    log.debug("Resolved image model from RunMetadata: {}", parsed.toCanonicalId());
                    return parsed;
                }
            }
        }

        // Check agent extensions for imageModel key
        Map<String, String> extensions = ctx.getAttribute(ATTR_AGENT_EXTENSIONS);
        if (extensions != null) {
            String imageModel = extensions.get("imageModel");
            if (imageModel != null && !imageModel.isBlank()) {
                ModelRef parsed = ModelRef.parse(imageModel.trim());
                if (parsed != null) {
                    log.debug("Resolved image model from agent extensions: {}", parsed.toCanonicalId());
                    return parsed;
                }
            }
        }

        // Fall back to primary chat model
        log.debug("Image model not configured, falling back to primary chat model");
        return resolveEffectiveModel(ctx);
    }

    /**
     * Resolves the effective provider string for the given agent context.
     *
     * <p>Precedence: typed RunMetadata.resolvedProvider &gt; map-based
     * runMetadata {@code "resolvedProvider"} &gt; agent-extension
     * {@code "provider"} &gt; provider parsed from the effective model &gt;
     * first-available provider from registry.
     *
     * @param ctx the agent context
     * @return the resolved provider string (never null)
     */
    public String resolveEffectiveProvider(AgentContext ctx) {
        // 1a. Typed RunMetadata override
        RunMetadata rm = ctx.getRunMetadata();
        if (rm != null) {
            String typedProvider = rm.getResolvedProvider();
            if (typedProvider != null && !typedProvider.isBlank()) {
                log.debug("Resolved provider from typed RunMetadata: {}", typedProvider);
                return typedProvider;
            }
        }

        // 1b. Map-based runMetadata override
        Object mapProvider = ctx.getRunMetadata(MDK_RESOLVED_PROVIDER);
        if (mapProvider instanceof String providerStr && !providerStr.isBlank()) {
            log.debug("Resolved provider from map-based runMetadata: {}", providerStr);
            return providerStr;
        }

        // 2. Agent-config extension
        Map<String, String> extensions = ctx.getAttribute(ATTR_AGENT_EXTENSIONS);
        if (extensions != null) {
            String extProvider = extensions.get(EXT_PROVIDER);
            if (extProvider != null && !extProvider.isBlank()) {
                log.debug("Resolved provider from agent extensions: {}", extProvider);
                return extProvider;
            }
        }

        // 3. Provider from the effective model
        ModelRef effectiveModel = resolveEffectiveModel(ctx);
        return effectiveModel.getProvider();
    }

    /**
     * Resolves the effective fallback chain for the given agent context.
     *
     * <p>Precedence: run-metadata {@code "fallbackChain"} (List or
     * comma-separated String) &gt; agent-extension {@code "fallback"}
     * (comma-separated) &gt; global {@code defaultFallbackChain}.
     *
     * @param ctx the agent context
     * @return the ordered fallback chain as an unmodifiable list (never null,
     *         may be empty)
     */
    public List<String> resolveEffectiveFallbacks(AgentContext ctx) {
        // 1. Run-metadata override (operator or sub-agent spawner)
        List<String> rmChain = resolveFallbackChainFromRunMetadata(ctx);
        if (rmChain != null && !rmChain.isEmpty()) {
            log.debug("Resolved fallback chain from run-metadata: {}", rmChain);
            return Collections.unmodifiableList(rmChain);
        }

        // 2. Agent-extension fallback (comma-separated string)
        Map<String, String> extensions = ctx.getAttribute(ATTR_AGENT_EXTENSIONS);
        if (extensions != null) {
            String extFallback = extensions.get(EXT_FALLBACK);
            if (extFallback != null && !extFallback.isBlank()) {
                List<String> chain = parseCommaSeparatedList(extFallback);
                if (!chain.isEmpty()) {
                    log.debug("Resolved fallback chain from agent extensions: {}", chain);
                    return Collections.unmodifiableList(chain);
                }
            }
        }

        // 3. Global default
        log.debug("Using global fallback chain: {}", defaultFallbackChain);
        return defaultFallbackChain;
    }

    // ── Alias resolution ──────────────────────────────────────────────────

    /**
     * Resolves a short alias to its canonical id ({@code "provider/model"}).
     *
     * <p>For example, {@code "gpt-4o"} resolves to {@code "openai/gpt-4o"}.
     * If the alias is not found in the alias map, the input is returned as-is
     * (it may already be a canonical id or a raw model name for which no
     * mapping exists).
     *
     * @param alias the alias to resolve (e.g. "gpt-4o" or "openai/gpt-4o")
     * @return the canonical id, or the input unchanged if no mapping exists
     */
    public String resolveAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return alias;
        }
        String trimmed = alias.trim();
        return aliasMap.getOrDefault(trimmed, trimmed);
    }

    // ── Catalog queries ───────────────────────────────────────────────────

    /**
     * Lists all currently available models from the catalog.
     *
     * @return unmodifiable list of catalog entries (never null, may be empty
     *         if the catalog is not initialized)
     */
    public List<ModelCatalogEntry> getAvailableModels() {
        if (catalog == null) {
            return Collections.emptyList();
        }
        List<ModelCatalogEntry> entries = catalog.listAll();
        return entries != null ? Collections.unmodifiableList(entries) : Collections.emptyList();
    }

    /**
     * Checks whether a specific model is currently registered and available.
     *
     * @param provider the provider name (must not be null)
     * @param model    the model name (must not be null)
     * @return true if the model is registered and available
     */
    public boolean isModelAvailable(String provider, String model) {
        if (provider == null || model == null) {
            return false;
        }
        return registry.hasModel(provider, model);
    }

    /**
     * Rebuilds the alias-to-canonical-id mapping from the current catalog
     * state.
     *
     * <p>For each catalog entry, the entry's alias (if present) is mapped to
     * its canonical id ({@code "provider/name"}). This method is safe to call
     * concurrently from any thread; the map swap is atomic from the caller's
     * perspective.
     */
    public void refreshAliasMap() {
        ConcurrentHashMap<String, String> newMap = new ConcurrentHashMap<>();
        if (catalog != null) {
            List<ModelCatalogEntry> entries = catalog.listAll();
            if (entries != null) {
                for (ModelCatalogEntry entry : entries) {
                    String canonicalId = entry.getProvider() + "/" + entry.getName();
                    String alias = entry.getAlias();
                    if (alias != null && !alias.isBlank()) {
                        newMap.put(alias.trim(), canonicalId);
                    }
                    // Also map the bare model name for direct lookup
                    String name = entry.getName();
                    if (name != null && !name.isBlank()) {
                        newMap.putIfAbsent(name.trim(), canonicalId);
                    }
                }
            }
        }
        aliasMap.clear();
        aliasMap.putAll(newMap);
        log.debug("Alias map refreshed: {} entries", aliasMap.size());
    }

    // ── Internal: run-metadata resolution ──────────────────────────────────

    /**
     * Attempts to resolve a ModelRef from run-metadata overrides.
     *
     * <p>Checks both the typed {@link RunMetadata} object and the legacy
     * map-based {@code runMetadataMap}, in that order. Returns null if no
     * override is present.
     */
    private ModelRef resolveFromRunMetadata(AgentContext ctx) {
        // 1a. Typed RunMetadata (structured, preferred path)
        RunMetadata rm = ctx.getRunMetadata();
        if (rm != null) {
            String typedModel = rm.getResolvedModel();
            String typedProvider = rm.getResolvedProvider();
            if (typedModel != null && !typedModel.isBlank()
                    && typedProvider != null && !typedProvider.isBlank()) {
                log.debug("Resolved model from typed RunMetadata: {}/{}", typedProvider, typedModel);
                return new ModelRef(typedProvider.trim(), typedModel.trim());
            }
            if (typedModel != null && !typedModel.isBlank()) {
                ModelRef parsed = ModelRef.parse(typedModel.trim());
                if (parsed != null) {
                    log.debug("Resolved model from typed RunMetadata (parsed): {}", parsed.toCanonicalId());
                    return parsed;
                }
            }
        }

        // 1b. Map-based runMetadata (legacy / backward compatible)
        Object mapModel = ctx.getRunMetadata(MDK_RESOLVED_MODEL);
        Object mapProvider = ctx.getRunMetadata(MDK_RESOLVED_PROVIDER);
        if (mapModel instanceof String modelStr && !modelStr.isBlank()
                && mapProvider instanceof String providerStr && !providerStr.isBlank()) {
            log.debug("Resolved model from map-based runMetadata: {}/{}", providerStr, modelStr);
            return new ModelRef(providerStr.trim(), modelStr.trim());
        }
        if (mapModel instanceof String modelStr && !modelStr.isBlank()) {
            ModelRef parsed = ModelRef.parse(modelStr.trim());
            if (parsed != null) {
                log.debug("Resolved model from map-based runMetadata (parsed): {}", parsed.toCanonicalId());
                return parsed;
            }
        }

        return null;
    }

    /**
     * Attempts to resolve a fallback chain from run-metadata overrides.
     *
     * <p>Accepts both a {@code List<String>} value and a comma-separated
     * {@code String} value under the {@code "fallbackChain"} key.
     */
    private List<String> resolveFallbackChainFromRunMetadata(AgentContext ctx) {
        Object rmChain = ctx.getRunMetadata(MDK_FALLBACK_CHAIN);
        if (rmChain instanceof List<?> list) {
            List<String> chain = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    chain.add(s.trim());
                }
            }
            if (!chain.isEmpty()) {
                return chain;
            }
        }
        if (rmChain instanceof String str && !str.isBlank()) {
            List<String> chain = parseCommaSeparatedList(str);
            if (!chain.isEmpty()) {
                return chain;
            }
        }
        return null;
    }

    // ── Internal: agent-extension resolution ──────────────────────────────

    /**
     * Attempts to resolve a ModelRef from agent-config extensions.
     *
     * <p>Reads the {@code "agentExtensions"} attribute from the context and
     * looks for {@code "model"} (canonical id) and {@code "provider"} keys.
     */
    private ModelRef resolveFromAgentExtensions(AgentContext ctx) {
        Map<String, String> extensions = ctx.getAttribute(ATTR_AGENT_EXTENSIONS);
        if (extensions == null) {
            return null;
        }

        String extModel = extensions.get(EXT_MODEL);
        String extProvider = extensions.get(EXT_PROVIDER);

        // Both provider and model explicitly set
        if (extModel != null && !extModel.isBlank()
                && extProvider != null && !extProvider.isBlank()) {
            log.debug("Resolved model from agent extensions (provider+model): {}/{}",
                    extProvider, extModel);
            return new ModelRef(extProvider.trim(), extModel.trim());
        }

        // Only model set — try parsing as canonical id
        if (extModel != null && !extModel.isBlank()) {
            ModelRef parsed = ModelRef.parse(extModel.trim());
            if (parsed != null) {
                log.debug("Resolved model from agent extensions (parsed): {}", parsed.toCanonicalId());
                return parsed;
            }
            // Not parseable as canonical — if provider is set, use them together
            if (extProvider != null && !extProvider.isBlank()) {
                return new ModelRef(extProvider.trim(), extModel.trim());
            }
        }

        return null;
    }

    // ── Internal: helpers ─────────────────────────────────────────────────

    /**
     * Attempts to parse a model string into a {@link ModelRef}.
     *
     * <p>First tries canonical parsing ({@code "provider/model"}). If that
     * fails and the string looks like a bare model name (no slash), resolves
     * it through the alias map.
     *
     * @param modelString the raw model string (may be null, blank, canonical,
     *                    or bare name)
     * @return a ModelRef, or null if the input cannot be resolved
     */
    private ModelRef parseModelString(String modelString) {
        if (modelString == null || modelString.isBlank()) {
            return null;
        }
        String trimmed = modelString.trim();

        // Try canonical parse first
        ModelRef parsed = ModelRef.parse(trimmed);
        if (parsed != null) {
            return parsed;
        }

        // Try alias resolution (bare model name → "provider/model")
        String resolved = aliasMap.get(trimmed);
        if (resolved != null) {
            return ModelRef.parse(resolved);
        }

        return null;
    }

    /**
     * Splits a comma-separated string into a list of trimmed, non-empty
     * segments.
     */
    private static List<String> parseCommaSeparatedList(String input) {
        List<String> result = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return result;
        }
        for (String part : input.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Returns the first canonical id from the default fallback chain as a
     * ModelRef, or null if the chain is empty.
     */
    private ModelRef firstFromFallbackChain() {
        if (defaultFallbackChain.isEmpty()) {
            return null;
        }
        return ModelRef.parse(defaultFallbackChain.get(0));
    }

    /**
     * Returns the first available model from the registry as a last-resort
     * fallback.
     *
     * @return a ModelRef for the first available model
     * @throws IllegalStateException if no models are registered in the registry
     */
    private ModelRef resolveFirstAvailable() {
        Map<String, List<ChatModel>> all = registry.getAll();
        for (Map.Entry<String, List<ChatModel>> entry : all.entrySet()) {
            List<ChatModel> models = entry.getValue();
            if (models != null && !models.isEmpty()) {
                return new ModelRef(entry.getKey(), models.get(0).model());
            }
        }
        throw new IllegalStateException(
                "No ChatModel registered in the registry. "
                        + "Configure at least one AI model provider.");
    }
}
