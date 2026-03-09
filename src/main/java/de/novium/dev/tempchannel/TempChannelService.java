package de.novium.dev.tempchannel;

import de.novium.dev.config.BotConfig;
import de.novium.dev.db.Database;
import de.novium.dev.panel.PanelManager;
import de.novium.dev.util.RomanNumerals;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class TempChannelService {

    private static final Logger logger = LoggerFactory.getLogger(TempChannelService.class);

    private final JDA jda;
    private final BotConfig config;
    private final Database database;
    private final PanelManager panelManager;

    private final ConcurrentHashMap<Long, TempChannelData> channels = new ConcurrentHashMap<>();

    /** Per-type lock to prevent concurrent channel creation/deletion races. */
    private final EnumMap<TempChannelType, ReentrantLock> typeLocks = new EnumMap<>(TempChannelType.class);

    /**
     * Tracks which channel numbers are currently in use per type.
     * Used to find the lowest free Roman-numeral number when creating a new channel.
     * Access is always guarded by the corresponding typeLock.
     */
    private final EnumMap<TempChannelType, Set<Integer>> usedNumbers = new EnumMap<>(TempChannelType.class);

    private final ExecutorService executor = Executors.newFixedThreadPool(
            4, r -> new Thread(r, "tempchannel-worker"));

    public TempChannelService(JDA jda, BotConfig config, Database database, PanelManager panelManager) {
        this.jda          = jda;
        this.config       = config;
        this.database     = database;
        this.panelManager = panelManager;

        for (TempChannelType type : TempChannelType.values()) {
            typeLocks.put(type, new ReentrantLock());
            usedNumbers.put(type, ConcurrentHashMap.newKeySet());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    public void initialize(Guild guild) {
        logger.info("Initialisiere TempChannelService für Guild '{}'", guild.getName());

        for (TempChannelData data : database.loadAll()) {
            VoiceChannel vc = guild.getVoiceChannelById(data.getChannelId());
            if (vc == null) {
                database.delete(data.getChannelId());
                continue;
            }
            channels.put(data.getChannelId(), data);

            // Restore used numbers so the counter stays consistent after restart
            if (data.getChannelNumber() > 0) {
                usedNumbers.get(data.getType()).add(data.getChannelNumber());
            }

            logger.info("Kanal '{}' wiederhergestellt (type={}, number={}, activated={})",
                    vc.getName(), data.getType(), data.getChannelNumber(), data.isActivated());
        }

        // Ensure at least one free channel exists per type
        for (TempChannelType type : TempChannelType.values()) {
            if (countFreeChannels(type) == 0) {
                createFreeChannel(guild, type);
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Voice event handlers ──────────────────────────────────────────────

    public void handleJoin(Member member, VoiceChannel channel) {
        TempChannelData data = channels.get(channel.getIdLong());
        if (data == null) return;

        executor.submit(() -> {
            ReentrantLock lock = typeLocks.get(data.getType());
            lock.lock();
            try {
                if (data.activate(member.getIdLong())) {
                    logger.info("Kanal '{}' aktiviert durch {}", channel.getName(), member.getEffectiveName());
                    if (countFreeChannels(data.getType()) == 0) {
                        createFreeChannel(member.getGuild(), data.getType());
                    }
                    panelManager.createPanel(member.getGuild(), channel, data);
                    database.save(data);
                }
            } finally {
                lock.unlock();
            }
        });
    }

    public void handleLeave(Member member, VoiceChannel channel) {
        TempChannelData data = channels.get(channel.getIdLong());
        if (data == null) return;

        executor.submit(() -> {
            Guild guild = member.getGuild();
            List<Member> remaining = channel.getMembers();
            if (remaining.isEmpty()) {
                onChannelBecameEmpty(guild, channel, data);
            } else if (data.getCreatorId() == member.getIdLong()) {
                transferOwnership(guild, channel, data, remaining.get(0));
            }
        });
    }

    // ── Channel operations ────────────────────────────────────────────────

    public void lockChannel(Guild guild, VoiceChannel channel, TempChannelData data) {
        if (data.isLocked()) return;
        for (Member m : channel.getMembers()) data.addToWhitelist(m.getIdLong());
        data.setLocked(true);
        applyLockPermissions(guild, channel, data);
        panelManager.updatePanel(guild, channel, data);
        database.save(data);
        logger.info("Kanal '{}' gesperrt (Whitelist-Größe={})", channel.getName(), data.getWhitelist().size());
    }

    public void unlockChannel(Guild guild, VoiceChannel channel, TempChannelData data) {
        if (!data.isLocked()) return;
        data.setLocked(false);
        data.getWhitelist().clear();
        applyUnlockPermissions(guild, channel);
        panelManager.updatePanel(guild, channel, data);
        database.save(data);
        logger.info("Kanal '{}' entsperrt", channel.getName());
    }

    public void transferOwnership(Guild guild, VoiceChannel channel, TempChannelData data, Member newOwner) {
        data.setCreatorId(newOwner.getIdLong());
        panelManager.updatePanel(guild, channel, data);
        database.save(data);
        logger.info("Ownership von '{}' übertragen an {}", channel.getName(), newOwner.getEffectiveName());
    }

    public void kickMember(Guild guild, Member target) {
        guild.kickVoiceMember(target).queue(
                v   -> logger.info("Mitglied {} aus Voice entfernt", target.getEffectiveName()),
                err -> logger.warn("Konnte {} nicht aus Voice entfernen: {}", target.getEffectiveName(), err.getMessage())
        );
    }

    public void setUserLimit(VoiceChannel channel, int newLimit) {
        int clamped = Math.max(1, Math.min(99, newLimit));
        channel.getManager().setUserLimit(clamped).queue(
                v   -> logger.info("Nutzerlimit von '{}' auf {} gesetzt", channel.getName(), clamped),
                err -> logger.warn("Konnte Nutzerlimit von '{}' nicht ändern: {}", channel.getName(), err.getMessage())
        );
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public TempChannelData getChannelData(long channelId) {
        return channels.get(channelId);
    }

    public boolean isCreator(long channelId, long memberId) {
        TempChannelData data = channels.get(channelId);
        return data != null && data.getCreatorId() == memberId;
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private void onChannelBecameEmpty(Guild guild, VoiceChannel channel, TempChannelData data) {
        ReentrantLock lock = typeLocks.get(data.getType());
        lock.lock();
        try {
            if (!data.deactivate()) return;

            long freeCount = countFreeChannels(data.getType());
            if (freeCount > 0) {
                // Another free channel already exists → delete this one
                deleteChannel(guild, channel, data);
            } else {
                // Keep as new idle channel, just remove the panel
                panelManager.deletePanel(guild, data);
                database.save(data);
                logger.info("Kanal '{}' bleibt als Idle-Kanal erhalten", channel.getName());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a new idle channel of the given type.
     * Assigns the lowest unused Roman-numeral number.
     *
     * Must only be called while holding the corresponding {@code typeLock}.
     */
    private void createFreeChannel(Guild guild, TempChannelType type) {
        Category category = findAvailableCategory(guild);
        if (category == null) {
            logger.error("Keine verfügbare Kategorie für Typ {}", type);
            return;
        }

        int number = nextFreeNumber(type);
        String name = type.getDisplayName() + " " + RomanNumerals.toRoman(number);

        try {
            VoiceChannel created = guild.createVoiceChannel(name, category)
                    .setUserlimit(type.getUserLimit())
                    .complete();

            TempChannelData data = new TempChannelData(created.getIdLong(), type);
            data.setChannelNumber(number);

            usedNumbers.get(type).add(number);
            channels.put(created.getIdLong(), data);
            database.save(data);

            logger.info("Freier Kanal '{}' (id={}) in '{}' erstellt",
                    created.getName(), created.getId(), category.getName());
        } catch (Exception e) {
            logger.error("Fehler beim Erstellen des freien Kanals vom Typ {}", type, e);
            // Roll back the reserved number so it can be retried
            usedNumbers.get(type).remove(number);
        }
    }

    private void deleteChannel(Guild guild, VoiceChannel channel, TempChannelData data) {
        channels.remove(channel.getIdLong());
        usedNumbers.get(data.getType()).remove(data.getChannelNumber());
        database.delete(channel.getIdLong());
        panelManager.deletePanel(guild, data);
        channel.delete().queue(
                v   -> logger.info("Temp-Kanal '{}' gelöscht", channel.getName()),
                err -> logger.warn("Kanal '{}' konnte nicht gelöscht werden: {}", channel.getName(), err.getMessage())
        );
    }

    private void applyLockPermissions(Guild guild, VoiceChannel channel, TempChannelData data) {
        channel.upsertPermissionOverride(guild.getPublicRole())
                .deny(Permission.VOICE_CONNECT)
                .queue();
        for (long memberId : data.getWhitelist()) {
            Member member = guild.getMemberById(memberId);
            if (member != null) {
                channel.upsertPermissionOverride(member).grant(Permission.VOICE_CONNECT).queue();
            }
        }
        for (long roleId : config.getBypassRoleIds()) {
            var role = guild.getRoleById(roleId);
            if (role != null) {
                channel.upsertPermissionOverride(role).grant(Permission.VOICE_CONNECT).queue();
            }
        }
    }

    private void applyUnlockPermissions(Guild guild, VoiceChannel channel) {
        var override = channel.getPermissionOverride(guild.getPublicRole());
        if (override != null) override.delete().queue();
        for (var mo : channel.getMemberPermissionOverrides()) mo.delete().queue();
    }

    private Category findAvailableCategory(Guild guild) {
        int maxChannels = config.getCategoryMaxChannels();
        for (long categoryId : config.getCategoryIds()) {
            Category category = guild.getCategoryById(categoryId);
            if (category == null) continue;
            if (category.getChannels().size() < maxChannels) return category;
        }
        return null;
    }

    private long countFreeChannels(TempChannelType type) {
        return channels.values().stream()
                .filter(d -> d.getType() == type && !d.isActivated())
                .count();
    }

    /**
     * Returns the lowest positive integer not currently used by any channel
     * of the given type. Access must be guarded by the corresponding typeLock.
     */
    private int nextFreeNumber(TempChannelType type) {
        Set<Integer> used = usedNumbers.get(type);
        int n = 1;
        while (used.contains(n)) n++;
        return n;
    }
}
