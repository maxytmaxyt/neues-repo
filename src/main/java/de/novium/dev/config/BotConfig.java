package de.novium.dev.config;

import de.max.botproperties.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Typed wrapper around {@link BotProperties}.
 * All configuration values are read once on startup.
 */
public class BotConfig {

    private static final Logger logger = LoggerFactory.getLogger(BotConfig.class);

    private final BotProperties props;

    public BotConfig(BotProperties props) {
        this.props = props;
    }

    public static BotConfig load(String filename) {
        BotProperties props = BotProperties.load(filename);
        logger.info("Configuration loaded from '{}'", filename);
        return new BotConfig(props);
    }

    // ── Required values ───────────────────────────────────────────────────

    public String getToken() {
        return props.getOrThrow("token");
    }

    public long getGuildId() {
        return Long.parseLong(props.getOrThrow("guild.id"));
    }

    public long getPanelChannelId() {
        return Long.parseLong(props.getOrThrow("panel.channel.id"));
    }

    /**
     * Returns the list of category IDs to use for temp channels, in priority order.
     * When the first category is full the bot moves on to the next.
     */
    public List<Long> getCategoryIds() {
        String raw = props.getOrThrow("categories");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
    }

    /**
     * Maximum number of channels per category before the bot switches
     * to the next one. Defaults to 45 to stay well below Discord's hard
     * limit of 50 channels per category.
     */
    public int getCategoryMaxChannels() {
        String raw = props.get("category.max.channels");
        if (raw == null || raw.isBlank()) return 45;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid category.max.channels value '{}', using default 45", raw);
            return 45;
        }
    }

    /** Optional: role IDs that are always allowed to join locked channels. */
    public List<Long> getBypassRoleIds() {
        String raw = props.get("bypass.role.ids");
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
    }
}
