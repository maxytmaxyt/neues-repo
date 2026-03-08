package de.novium.dev.tempchannel;

import de.novium.dev.config.BotConfig;
import de.novium.dev.db.Database;
import de.novium.dev.panel.PanelManager;
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
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class TempChannelService {

    private static final Logger logger = LoggerFactory.getLogger(TempChannelService.class);

    private final JDA jda;
    private final BotConfig config;
    private final Database database;
    private final PanelManager panelManager;

    // Alle bekannten Temp-Kanäle: channelId → Zustand
    private final ConcurrentHashMap<Long, TempChannelData> channels = new ConcurrentHashMap<>();

    // Pro Kanaltyp ein Lock, damit nie zwei Threads gleichzeitig einen Kanal erstellen/löschen
    private final EnumMap<TempChannelType, ReentrantLock> typeLocks = new EnumMap<>(TempChannelType.class);

    // Eigener Thread-Pool für Discord API Calls – der JDA Event Thread wird nie blockiert
    private final ExecutorService executor = Executors.newFixedThreadPool(
            4, r -> new Thread(r, "tempchannel-worker")
    );

    public TempChannelService(JDA jda, BotConfig config, Database database, PanelManager panelManager) {
        this.jda = jda;
        this.config = config;
        this.database = database;
        this.panelManager = panelManager;

        for (TempChannelType type : TempChannelType.values()) {
            typeLocks.put(type, new ReentrantLock());
        }
    }

    public void initialize(Guild guild) {
        logger.info("Initialisiere TempChannels für Guild '{}'", guild.getName());

        // Gespeicherte Kanäle aus der DB laden
        for (TempChannelData data : database.loadAll()) {
            VoiceChannel vc = guild.getVoiceChannelById(data.getChannelId());
            if (vc == null) {
                logger.info("Kanal {} existiert nicht mehr, wird entfernt", data.getChannelId());
                database.delete(data.getChannelId());
                continue;
            }
            channels.put(data.getChannelId(), data);
            logger.info("Kanal '{}' wiederhergestellt (type={}, aktiv={})", vc.getName(), data.getType(), data.isActivated());
        }

        // Sicherstellen dass für jeden Typ mind. ein freier Kanal da ist
        for (TempChannelType type : TempChannelType.values()) {
            if (countFreeChannels(type) == 0) {
                createFreeChannel(guild, type);
            }
        }
    }

    public void handleJoin(Member member, VoiceChannel channel) {
        TempChannelData data = channels.get(channel.getIdLong());
        if (data == null) return;

        executor.submit(() -> {
            ReentrantLock lock = typeLocks.get(data.getType());
            lock.lock();
            try {
                if (data.activate(member.getIdLong())) {
                    logger.info("Kanal '{}' aktiviert von {}", channel.getName(), member.getEffectiveName());

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
            List<Member> remaining = channel.getMembers();

            if (remaining.isEmpty()) {
                onChannelBecameEmpty(member.getGuild(), channel, data);
            } else if (data.getCreatorId() == member.getIdLong()) {
                // Creator weg aber andere noch drin → Ownership weitergeben
                transferOwnership(member.getGuild(), channel, data, remaining.get(0));
            }
        });
    }

    public void lockChannel(Guild guild, VoiceChannel channel, TempChannelData data) {
        if (data.isLocked()) return;

        for (Member m : channel.getMembers()) {
            data.addToWhitelist(m.getIdLong());
        }
        data.setLocked(true);

        applyLockPermissions(guild, channel, data);
        panelManager.updatePanel(guild, channel, data);
        database.save(data);

        logger.info("Kanal '{}' gesperrt (Whitelist: {} Nutzer)", channel.getName(), data.getWhitelist().size());
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
        logger.info("Ownership von '{}' an {} übergeben", channel.getName(), newOwner.getEffectiveName());
    }

    public void kickMember(Guild guild, Member target) {
        guild.kickVoiceMember(target).queue(
                v   -> logger.info("{} aus Voice gekickt", target.getEffectiveName()),
                err -> logger.warn("Kick von {} fehlgeschlagen: {}", target.getEffectiveName(), err.getMessage())
        );
    }

    public void setUserLimit(VoiceChannel channel, int newLimit) {
        int clamped = Math.max(1, Math.min(99, newLimit));
        channel.getManager().setUserLimit(clamped).queue(
                v   -> logger.info("Limit von '{}' auf {} gesetzt", channel.getName(), clamped),
                err -> logger.warn("Limit-Änderung für '{}' fehlgeschlagen: {}", channel.getName(), err.getMessage())
        );
    }

    public TempChannelData getChannelData(long channelId) {
        return channels.get(channelId);
    }

    public boolean isCreator(long channelId, long memberId) {
        TempChannelData data = channels.get(channelId);
        return data != null && data.getCreatorId() == memberId;
    }

    private void onChannelBecameEmpty(Guild guild, VoiceChannel channel, TempChannelData data) {
        ReentrantLock lock = typeLocks.get(data.getType());
        lock.lock();
        try {
            if (!data.deactivate()) return;

            if (countFreeChannels(data.getType()) > 0) {
                // Es gibt schon einen freien Kanal dieses Typs → diesen löschen
                deleteChannel(guild, channel, data);
            } else {
                // Keiner mehr frei → diesen behalten
                panelManager.deletePanel(guild, data);
                database.save(data);
                logger.info("Kanal '{}' ist jetzt wieder frei", channel.getName());
            }
        } finally {
            lock.unlock();
        }
    }

    private void createFreeChannel(Guild guild, TempChannelType type) {
        Category category = findAvailableCategory(guild);
        if (category == null) {
            logger.error("Keine freie Kategorie für Typ {} gefunden!", type);
            return;
        }

        try {
            VoiceChannel created = guild.createVoiceChannel(type.getDisplayName(), category)
                    .setUserlimit(type.getUserLimit())
                    .complete();

            TempChannelData data = new TempChannelData(created.getIdLong(), type);
            channels.put(created.getIdLong(), data);
            database.save(data);

            logger.info("Kanal '{}' erstellt in '{}'", created.getName(), category.getName());
        } catch (Exception e) {
            logger.error("Kanal-Erstellung für Typ {} fehlgeschlagen", type, e);
        }
    }

    private void deleteChannel(Guild guild, VoiceChannel channel, TempChannelData data) {
        channels.remove(channel.getIdLong());
        database.delete(channel.getIdLong());
        panelManager.deletePanel(guild, data);

        channel.delete().queue(
                v   -> logger.info("Kanal '{}' gelöscht", channel.getName()),
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
                channel.upsertPermissionOverride(member)
                        .grant(Permission.VOICE_CONNECT)
                        .queue();
            }
        }

        for (long roleId : config.getBypassRoleIds()) {
            var role = guild.getRoleById(roleId);
            if (role != null) {
                channel.upsertPermissionOverride(role)
                        .grant(Permission.VOICE_CONNECT)
                        .queue();
            }
        }
    }

    private void applyUnlockPermissions(Guild guild, VoiceChannel channel) {
        var override = channel.getPermissionOverride(guild.getPublicRole());
        if (override != null) override.delete().queue();

        for (var mo : channel.getMemberPermissionOverrides()) {
            mo.delete().queue();
        }
    }

    private Category findAvailableCategory(Guild guild) {
        int max = config.getCategoryMaxChannels();

        for (long categoryId : config.getCategoryIds()) {
            Category category = guild.getCategoryById(categoryId);
            if (category == null) {
                logger.warn("Kategorie {} nicht gefunden", categoryId);
                continue;
            }
            if (category.getChannels().size() < max) {
                return category;
            }
            logger.warn("Kategorie '{}' ist voll, nächste wird versucht", category.getName());
        }
        return null;
    }

    private long countFreeChannels(TempChannelType type) {
        return channels.values().stream()
                .filter(d -> d.getType() == type && !d.isActivated())
                .count();
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
