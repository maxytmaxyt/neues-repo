package de.novium.dev.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Typed wrapper around Java's built-in {@link Properties}.
 * All configuration values are read once on startup.
 */
public class BotConfig {

    private static final Logger logger = LoggerFactory.getLogger(BotConfig.class);

    private final Properties props;

    public BotConfig(Properties props) {
        this.props = props;
    }

    public static BotConfig load(String filename) {
        Properties props = new Properties();
        try (InputStream is = new FileInputStream(filename)) {
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Konfigurationsdatei '" + filename + "' konnte nicht geladen werden", e);
        }
        logger.info("Konfiguration geladen aus '{}'", filename);
        return new BotConfig(props);
    }



    private String getOrThrow(String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Pflicht-Property '" + key + "' fehlt in der Konfiguration");
        }
        return value.trim();
    }

    private String getOptional(String key) {
        String value = props.getProperty(key);
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }
    public String getToken() {
        return getOrThrow("token");
    }

    public long getGuildId() {
        return Long.parseLong(getOrThrow("guild.id"));
    }

    public long getPanelChannelId() {
        return Long.parseLong(getOrThrow("panel.channel.id"));
    }

    public List<Long> getCategoryIds() {
        String raw = getOrThrow("categories");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
    }

    public int getCategoryMaxChannels() {
        String raw = getOptional("category.max.channels");
        if (raw == null) return 45;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            logger.warn("Ungültiger Wert für category.max.channels '{}', verwende Standard 45", raw);
            return 45;
        }
    }

    public List<Long> getBypassRoleIds() {
        String raw = getOptional("bypass.role.ids");
        if (raw == null) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
    }
}
