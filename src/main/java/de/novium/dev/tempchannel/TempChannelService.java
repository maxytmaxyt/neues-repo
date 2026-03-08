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
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central service that manages the lifecycle of all temporary voice channels.
 *
 * ── Thread-safety model ──────────────────────────────────────────────────────
 *
 *  • The channel registry ({@code channels}) is a {@link ConcurrentHashMap}
 *    and safe for concurrent reads/writes.
 *
 *  • Per-type {@link ReentrantLock}s guard the "check-then-act" sections
 *    (counting empty channels, creating/deleting) so that no two threads
 *    can simultaneously decide to create or delete for the same type.
 *
 *  • {@link TempChannelData#activate} and {@link TempChannelData#deactivate}
 *    use {@code AtomicBoolean.compareAndSet} internally, giving an additional
 *    layer of protection for the individual channel state.
 *
 *  • All Discord REST calls run on the {@code executor} pool, never on the
 *    JDA event thread, so the event dispatch queue is never blocked.
 */
public class TempChannelService {

    private static final Logger logger = LoggerFactory.getLogger(TempChannelService.class);

    private final JDA jda;
    private final BotConfig config;
    private final Database database;
    private final PanelManager panelManager;

    /** Channel registry: channelId → state */
    private final ConcurrentHashMap<Long, TempChannelData> channels = new ConcurrentHashMap<>();

    /**
     * One lock per channel type ensures that creating a new free channel
     * and removing an obsolete empty one are mutually exclusive.
     */
    private final EnumMap<TempChannelType, ReentrantLock> typeLocks = new EnumMap<>(TempChannelType.class);

    /**
     * Dedicated thread pool for Discord API calls so we never block JDA's
     * event dispatch thread.
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(
            4, r -> new Thread(r, "tempchannel-worker")
    );

    public TempChannelService(JDA jda, BotConfig config, Database database, PanelManager panelManager) {
        this.jda          = jda;
        this.config       = config;
        this.database     = database;
        this.panelManager = panelManager;

        for (TempChannelType type : TempChannelType.values()) {
            typeLocks.put(type, new ReentrantLock());
        }
    }

    // ── Startup ───────────────────────────────────────────────────────────

    /**
     * Called once after JDA is ready.
     * Restores persisted state and ensures each type has at least one free channel.
     */
    public void initialize(Guild guild) {
        logger.info("Initializing TempChannelService for guild '{}'", guild.getName());

        // Restore persisted channels
        for (TempChannelData data : database.loadAll()) {
            VoiceChannel vc = guild.getVoiceChannelById(data.getChannelId());
            if (vc == null) {
                logger.info("Persisted channel {} no longer exists, removing", data.getChannelId());
                database.delete(data.getChannelId());
                continue;
            }
            channels.put(data.getChannelId(), data);
            logger.info("Restored channel '{}' (type={}, activated={})",
                    vc.getName(), data.getType(), data.isActivated());
        }

        // Ensure at least one free channel exists for every type
        for (TempChannelType type : TempChannelType.values()) {
            if (countFreeChannels(type) == 0) {
                createFreeChannel(guild, type);
            }
        }
    }

    // ── Event handlers (called from VoiceStateListener) ───────────────────

    /**
     * A member has joined a voice channel.
     * Off-loads work to the executor to avoid blocking the JDA event thread.
     */
    public void handleJoin(Member member, VoiceChannel channel) {
        TempChannelData data = channels.get(channel.getIdLong());
        if (data == null) return; // not a managed channel

        executor.submit(() -> {
            ReentrantLock lock = typeLocks.get(data.getType());
            lock.lock();
            try {
                // Try to activate this channel for the joining member.
                // activate() uses compareAndSet internally, so only one
                // concurrent caller wins even without the outer lock –
                // the lock is still needed to safely count + create below.
                if (data.activate(member.getIdLong())) {
                    logger.info("Channel '{}' activated by {}", channel.getName(), member.getEffectiveName());

                    // Make sure there is still a free channel of this type
                    if (countFreeChannels(data.getType()) == 0) {
                        createFreeChannel(member.getGuild(), data.getType());
                    }

                    // Send panel
                    panelManager.createPanel(member.getGuild(), channel, data);

                    // Persist
                    database.save(data);
                }
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * A member has left a voice channel.
     * Off-loads work to the executor.
     */
    public void handleLeave(Member member, VoiceChannel channel) {
        TempChannelData data = channels.get(channel.getIdLong());
        if (data == null) return;

        executor.submit(() -> {
            Guild guild = member.getGuild();

            // Re-fetch current member list from JDA cache
            List<Member> remaining = channel.getMembers();

            if (remaining.isEmpty()) {
                onChannelBecameEmpty(guild, channel, data);
            } else if (data.getCreatorId() == member.getIdLong()) {
                // Creator left but others remain → auto-transfer ownership
                transferOwnership(guild, channel, data, remaining.get(0));
            }
        });
    }

    // ── Lock / Unlock ─────────────────────────────────────────────────────

    public void lockChannel(Guild guild, VoiceChannel channel, TempChannelData data) {
        if (data.isLocked()) return;

        // Whitelist everyone currently inside
        for (Member m : channel.getMembers()) {
            data.addToWhitelist(m.getIdLong());
        }

        data.setLocked(true);

        // Deny @everyone CONNECT, grant whitelisted members
        applyLockPermissions(guild, channel, data);

        panelManager.updatePanel(guild, channel, data);
        database.save(data);

        logger.info("Channel '{}' locked by creator (whitelist: {} members)",
                channel.getName(), data.getWhitelist().size());
    }

    public void unlockChannel(Guild guild, VoiceChannel channel, TempChannelData data) {
        if (!data.isLocked()) return;

        data.setLocked(false);
        data.getWhitelist().clear();

        // Restore default permissions
        applyUnlockPermissions(guild, channel);

        panelManager.updatePanel(guild, channel, data);
        database.save(data);

        logger.info("Channel '{}' unlocked", channel.getName());
    }

    // ── Ownership transfer ────────────────────────────────────────────────

    public void transferOwnership(Guild guild, VoiceChannel channel, TempChannelData data, Member newOwner) {
        data.setCreatorId(newOwner.getIdLong());

        panelManager.updatePanel(guild, channel, data);
        database.save(data);

        logger.info("Ownership of '{}' transferred to {}", channel.getName(), newOwner.getEffectiveName());
    }

    // ── Kick ─────────────────────────────────────────────────────────────

    public void kickMember(Guild guild, Member target) {
        guild.kickVoiceMember(target).queue(
                v   -> logger.info("Kicked {} from voice", target.getEffectiveName()),
                err -> logger.warn("Could not kick {} from voice: {}", target.getEffectiveName(), err.getMessage())
        );
    }

    // ── User limit ────────────────────────────────────────────────────────

    public void setUserLimit(VoiceChannel channel, int newLimit) {
        int clamped = Math.max(1, Math.min(99, newLimit));
        channel.getManager().setUserLimit(clamped).queue(
                v   -> logger.info("User limit of '{}' changed to {}", channel.getName(), clamped),
                err -> logger.warn("Could not change user limit for '{}': {}", channel.getName(), err.getMessage())
        );
    }

    // ── Registry helpers ──────────────────────────────────────────────────

    public TempChannelData getChannelData(long channelId) {
        return channels.get(channelId);
    }

    public boolean isCreator(long channelId, long memberId) {
        TempChannelData data = channels.get(channelId);
        return data != null && data.getCreatorId() == memberId;
    }

    // ── Internal: channel lifecycle ───────────────────────────────────────

    private void onChannelBecameEmpty(Guild guild, VoiceChannel channel, TempChannelData data) {
        ReentrantLock lock = typeLocks.get(data.getType());
        lock.lock();
        try {
            // Only deactivate once (handles two near-simultaneous leave events)
            if (!data.deactivate()) return;

            // How many free channels remain after this one goes idle?
            long freeCount = countFreeChannels(data.getType());

            if (freeCount > 0) {
                // Another free channel already exists → delete this one
                deleteChannel(guild, channel, data);
            } else {
                // This is now the only free channel → keep it
                panelManager.deletePanel(guild, data);
                database.save(data);
                logger.info("Channel '{}' is now free (kept as idle channel)", channel.getName());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a new free (empty) channel of the given type.
     * MUST be called while holding the corresponding type lock.
     */
    private void createFreeChannel(Guild guild, TempChannelType type) {
        Category category = findAvailableCategory(guild);
        if (category == null) {
            logger.error("No available category found for type {}! All categories are full.", type);
            return;
        }

        String name = type.getDisplayName();
        try {
            VoiceChannel created = guild.createVoiceChannel(name, category)
                    .setUserlimit(type.getUserLimit())
                    .complete();

            TempChannelData data = new TempChannelData(created.getIdLong(), type);
            channels.put(created.getIdLong(), data);
            database.save(data);

            logger.info("Created free channel '{}' (id={}) in category '{}'",
                    created.getName(), created.getId(), category.getName());
        } catch (Exception e) {
            logger.error("Failed to create free channel of type {}", type, e);
        }
    }

    private void deleteChannel(Guild guild, VoiceChannel channel, TempChannelData data) {
        // Remove from registry first so any in-flight events ignore it
        channels.remove(channel.getIdLong());
        database.delete(channel.getIdLong());
        panelManager.deletePanel(guild, data);

        channel.delete().queue(
                v   -> logger.info("Deleted temp channel '{}'", channel.getName()),
                err -> logger.warn("Failed to delete channel '{}': {}", channel.getName(), err.getMessage())
        );
    }

    // ── Internal: permission helpers ──────────────────────────────────────

    private void applyLockPermissions(Guild guild, VoiceChannel channel, TempChannelData data) {
        // Deny CONNECT to everyone
        channel.upsertPermissionOverride(guild.getPublicRole())
                .deny(Permission.VOICE_CONNECT)
                .queue();

        // Grant CONNECT to every whitelisted member
        for (long memberId : data.getWhitelist()) {
            Member member = guild.getMemberById(memberId);
            if (member != null) {
                channel.upsertPermissionOverride(member)
                        .grant(Permission.VOICE_CONNECT)
                        .queue();
            }
        }

        // Grant bypass roles (configured in bot.properties)
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
        // Remove the @everyone deny override
        var override = channel.getPermissionOverride(guild.getPublicRole());
        if (override != null) {
            override.delete().queue();
        }

        // Remove all member-specific overrides added during lock
        for (var mo : channel.getMemberPermissionOverrides()) {
            mo.delete().queue();
        }
    }

    // ── Internal: category selection ──────────────────────────────────────

    /**
     * Returns the first configured category that still has room for a new channel.
     */
    private Category findAvailableCategory(Guild guild) {
        int maxChannels = config.getCategoryMaxChannels();

        for (long categoryId : config.getCategoryIds()) {
            Category category = guild.getCategoryById(categoryId);
            if (category == null) {
                logger.warn("Configured category {} not found in guild", categoryId);
                continue;
            }
            if (category.getChannels().size() < maxChannels) {
                return category;
            }
            logger.warn("Category '{}' is full ({}/{}), trying next",
                    category.getName(), category.getChannels().size(), maxChannels);
        }
        return null;
    }

    // ── Internal: counting ────────────────────────────────────────────────

    /** Counts channels of the given type that are currently free (not activated). */
    private long countFreeChannels(TempChannelType type) {
        return channels.values().stream()
                .filter(d -> d.getType() == type && !d.isActivated())
                .count();
    }

    // ── Shutdown ──────────────────────────────────────────────────────────

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
