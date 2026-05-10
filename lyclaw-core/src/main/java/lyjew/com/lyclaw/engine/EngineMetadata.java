package lyjew.com.lyclaw.engine;

import java.util.List;
import java.util.Set;

public class EngineMetadata {

    private final String name;
    private final String version;
    private final String description;
    private final List<String> supportedModels;
    private final Set<String> capabilities;

    public EngineMetadata(String name, String version, String description,
                          List<String> supportedModels, Set<String> capabilities) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.supportedModels = supportedModels;
        this.capabilities = capabilities;
    }

    public String getName() { return name; }

    public String getVersion() { return version; }

    public String getDescription() { return description; }

    public List<String> getSupportedModels() { return supportedModels; }

    public Set<String> getCapabilities() { return capabilities; }
}
