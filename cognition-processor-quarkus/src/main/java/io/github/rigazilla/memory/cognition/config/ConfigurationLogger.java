package io.github.rigazilla.memory.cognition.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.Properties;
import java.util.TreeSet;

/**
 * Logs all system properties and application configuration at startup.
 * Sensitive values (containing "key", "password", "secret", "token") are masked.
 */
@ApplicationScoped
public class ConfigurationLogger {

    private static final Logger LOG = Logger.getLogger(ConfigurationLogger.class);

    @Inject
    Config config;

    void onStart(@Observes StartupEvent event) {
        // Log System Properties
        LOG.info("=== System Properties ===");
        Properties props = System.getProperties();
        TreeSet<String> sortedKeys = new TreeSet<>(props.stringPropertyNames());
        
        for (String key : sortedKeys) {
            String value = isSensitive(key) ? "***MASKED***" : props.getProperty(key);
            LOG.infof("%s = %s", key, value);
        }
        LOG.info("=========================");
        
        // Log Application Configuration
        LOG.info("=== Application Configuration ===");
        TreeSet<String> sortedConfigKeys = new TreeSet<>();
        config.getPropertyNames().forEach(sortedConfigKeys::add);
        
        for (String key : sortedConfigKeys) {
            config.getOptionalValue(key, String.class).ifPresent(value -> {
                String displayValue = isSensitive(key) ? "***MASKED***" : value;
                LOG.infof("%s = %s", key, displayValue);
            });
        }
        LOG.info("==================================");
    }

    private boolean isSensitive(String key) {
        String lowerKey = key.toLowerCase();
        return lowerKey.contains("key")
            || lowerKey.contains("password")
            || lowerKey.contains("secret")
            || lowerKey.contains("token");
    }
}

// Made with Bob
