package de.novium.dev.panel;

import de.novium.dev.tempchannel.TempChannelData;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.time.Instant;
import java.util.List;

/**
 * Manages the per-channel management panel messages that appear in the
 * designated panel text channel.
 *
 * All Discord REST calls inside this class are executed via {@code .complete()}
 * which is acceptable because this class is only ever called from the
 * bot's own executor threads, never from the JDA event thread.
 */
public class PanelManager {

    private static final Logger logger = LoggerFactory.getLogger(PanelManager.class);

    private final long panelChannelId;

    public PanelManager(long panelChannelId) {
        this.panelChannelId = panelChannelId;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Sends a new panel message for the given channel and stores its ID
     * in {@code data}. Also persists the state via the caller.
     */
    public void createPanel(Guild guild, VoiceChannel channel, TempChannelData data) {
        TextChannel panelChannel = guild.getTextChannelById(panelChannelId);
        if (panelChannel == null) {
            logger.warn("Panel channel {} not found, skipping panel creation", panelChannelId);
            return;
        }

        var message = new MessageCreateBuilder()
                .setEmbeds(buildEmbed(guild, channel, data))
                .setComponents(buildButtons(data))
                .build();

        try {
            var sent = panelChannel.sendMessage(message).complete();
            data.setPanelMessageId(sent.getId());
            logger.info("Created panel message {} for channel '{}'", sent.getId(), channel.getName());
        } catch (Exception e) {
            logger.error("Failed to create panel message for channel {}", channel.getName(), e);
        }
    }

    /**
     * Edits the existing panel message to reflect the current channel state.
     * If the message no longer exists, a new one is created.
     */
    public void updatePanel(Guild guild, VoiceChannel channel, TempChannelData data) {
        if (data.getPanelMessageId() == null) {
            createPanel(guild, channel, data);
            return;
        }

        TextChannel panelChannel = guild.getTextChannelById(panelChannelId);
        if (panelChannel == null) return;

        var edit = new MessageEditBuilder()
                .setEmbeds(buildEmbed(guild, channel, data))
                .setComponents(buildButtons(data))
                .build();

        panelChannel.editMessageById(data.getPanelMessageId(), edit).queue(
                msg -> logger.debug("Updated panel for channel '{}'", channel.getName()),
                err -> {
                    logger.warn("Panel message for channel '{}' gone, recreating", channel.getName());
                    data.setPanelMessageId(null);
                    createPanel(guild, channel, data);
                }
        );
    }

    /**
     * Deletes the panel message when the channel is removed.
     */
    public void deletePanel(Guild guild, TempChannelData data) {
        if (data.getPanelMessageId() == null) return;

        TextChannel panelChannel = guild.getTextChannelById(panelChannelId);
        if (panelChannel == null) return;

        panelChannel.deleteMessageById(data.getPanelMessageId()).queue(
                v   -> logger.debug("Deleted panel message {}", data.getPanelMessageId()),
                err -> logger.warn("Could not delete panel message {} (already gone?)", data.getPanelMessageId())
        );
        data.setPanelMessageId(null);
    }

    // ── Embed builder ─────────────────────────────────────────────────────

    private MessageEmbed buildEmbed(Guild guild, VoiceChannel channel, TempChannelData data) {
        Member creator = guild.getMemberById(data.getCreatorId());
        String creatorMention = creator != null ? creator.getAsMention() : "*(nicht im Server)*";

        boolean locked = data.isLocked();

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("🔊 " + channel.getName() + "  –  Kanalverwaltung")
                .setColor(locked ? new Color(0xE74C3C) : new Color(0x3498DB))
                .addField("Ersteller", creatorMention, true)
                .addField("Typ", data.getType().getDisplayName()
                        + " *(max. " + data.getType().getUserLimit() + " Nutzer)*", true)
                .addField("Status", locked ? "🔒  Gesperrt" : "🔓  Offen", true)
                .setFooter("Kanal-ID: " + channel.getId())
                .setTimestamp(Instant.now());

        if (locked) {
            int wlSize = data.getWhitelist().size();
            eb.addField("Whitelist", wlSize + " Nutzer dürfen rejoinen", true);
        }

        int memberCount = channel.getMembers().size();
        eb.addField("Im Kanal", memberCount + " / " + channel.getUserLimit() + " Nutzer", true);

        return eb.build();
    }

    // ── Button builder ────────────────────────────────────────────────────

    private List<ActionRow> buildButtons(TempChannelData data) {
        String cid = String.valueOf(data.getChannelId());

        Button lockBtn = data.isLocked()
                ? Button.success("tc:unlock:" + cid, "🔓 Entsperren")
                : Button.danger("tc:lock:" + cid,    "🔒 Sperren");

        Button transferBtn = Button.secondary("tc:transfer:" + cid, "👑 Ownership");
        Button kickBtn     = Button.secondary("tc:kick:" + cid,     "👢 Nutzer kicken");
        Button limitBtn    = Button.secondary("tc:limit:" + cid,    "🔢 Limit ändern");

        return List.of(
                ActionRow.of(lockBtn, transferBtn),
                ActionRow.of(kickBtn, limitBtn)
        );
    }
}
