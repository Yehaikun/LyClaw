package lyjew.com.lyclaw.autoconfigure.config;

import lyjew.com.lyclaw.autoconfigure.facade.ExtensionProperties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "lyclaw")
public class LyClawConfigurationProperties {

    @NestedConfigurationProperty
    private ExtensionProperties extension = new ExtensionProperties();

    public ExtensionProperties getExtension() {
        return extension;
    }

    public void setExtension(ExtensionProperties extension) {
        this.extension = extension;
    }
}
