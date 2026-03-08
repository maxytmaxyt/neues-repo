package de.novium.dev.listener;

import de.novium.dev.tempchannel.TempChannelData;
import de.novium.dev.tempchannel.TempChannelService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles all button clicks, select-menu submissions, and modal responses
 * that relate to the tempchannel management panel.
 *
 * All custom IDs follow the pattern:  {@code tc:<action>:<channelId>}
 */
public class InteractionListener extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(InteractionListener.class);
    private static final String PREFIX = "tc:";

    private final TempChannelService service;

    public InteractionListener(TempChannelService service) {
        this.service = service;
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;

        // id format: tc:<action>:<channelId>
        String[] parts = id.split(":", 3);
        if (parts.length < 3) return;

        String action    = parts[1];
        long   channelId = parseLong(parts[2]);
        if (channelId == -1) return;

        Guild guild = event.getGuild();
        if (guild == null) return;

        TempChannelData data = service.getChannelData(channelId);
        if (data == null) {
            event.reply("❌ Dieser Kanal existiert nicht mehr.").setEphemeral(true).queue();
            return;
        }

        VoiceChannel channel = guild.getVoiceChannelById(channelId);
        if (channel == null) {
            event.reply("❌ Dieser Kanal existiert nicht mehr.").setEphemeral(true).queue();
            return;
        }

        // Only the creator may interact with the panel
        if (!service.isCreator(channelId, event.getUser().getIdLong())) {
            event.reply("❌ Nur der Kanalersteller darf das Panel bedienen.").setEphemeral(true).queue();
            return;
        }

        switch (action) {
            case "lock"     -> handleLock(event, guild, channel, data);
            case "unlock"   -> handleUnlock(event, guild, channel, data);
            case "transfer" -> handleTransferPrompt(event, guild, channel);
            case "kick"     -> handleKickPrompt(event, guild, channel, data);
            case "limit"    -> handleLimitPrompt(event, channelId);
            default         -> event.reply("❌ Unbekannte Aktion.").setEphemeral(true).queue();
        }
    }

    // ── Select menus ──────────────────────────────────────────────────────

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;

        String[] parts = id.split(":", 3);
        if (parts.length < 3) return;

        String action    = parts[1];
        long   channelId = parseLong(parts[2]);
        if (channelId == -1) return;

        Guild guild = event.getGuild();
        if (guild == null) return;

        TempChannelData data = service.getChannelData(channelId);
        VoiceChannel    channel = guild.getVoiceChannelById(channelId);

        if (data == null || channel == null) {
            event.reply("❌ Kanal nicht gefunden.").setEphemeral(true).queue();
            return;
        }

        if (!service.isCreator(channelId, event.getUser().getIdLong())) {
            event.reply("❌ Nur der Kanalersteller darf das tun.").setEphemeral(true).queue();
            return;
        }

        if (event.getValues().isEmpty()) {
            event.reply("❌ Keine Auswahl getroffen.").setEphemeral(true).queue();
            return;
        }

        long targetId = parseLong(event.getValues().get(0));
        Member target = guild.getMemberById(targetId);

        if (target == null) {
            event.reply("❌ Mitglied nicht gefunden.").setEphemeral(true).queue();
            return;
        }

        switch (action) {
            case "select:kick" -> {
                if (!channel.getMembers().contains(target)) {
                    event.reply("❌ " + target.getEffectiveName() + " ist nicht im Kanal.")
                            .setEphemeral(true).queue();
                    return;
                }
                service.kickMember(guild, target);
                event.reply("✅ " + target.getAsMention() + " wurde aus dem Kanal entfernt.")
                        .setEphemeral(true).queue();
            }
            case "select:transfer" -> {
                if (!channel.getMembers().contains(target)) {
                    event.reply("❌ " + target.getEffectiveName() + " ist nicht im Kanal.")
                            .setEphemeral(true).queue();
                    return;
                }
                service.transferOwnership(guild, channel, data, target);
                event.reply("✅ Ownership übertragen an " + target.getAsMention() + ".")
                        .setEphemeral(true).queue();
            }
            default -> event.reply("❌ Unbekannte Aktion.").setEphemeral(true).queue();
        }
    }

    // ── Modals ────────────────────────────────────────────────────────────

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith(PREFIX)) return;

        // id format: tc:modal:limit:<channelId>
        String[] parts = id.split(":", 4);
        if (parts.length < 4) return;

        long channelId = parseLong(parts[3]);
        if (channelId == -1) return;

        Guild guild = event.getGuild();
        if (guild == null) return;

        VoiceChannel channel = guild.getVoiceChannelById(channelId);
        if (channel == null) {
            event.reply("❌ Kanal nicht gefunden.").setEphemeral(true).queue();
            return;
        }

        if (!service.isCreator(channelId, event.getUser().getIdLong())) {
            event.reply("❌ Nur der Kanalersteller darf das tun.").setEphemeral(true).queue();
            return;
        }

        String rawLimit = event.getValue("new_limit") != null
                ? event.getValue("new_limit").getAsString()
                : "";

        int newLimit;
        try {
            newLimit = Integer.parseInt(rawLimit.trim());
        } catch (NumberFormatException e) {
            event.reply("❌ Ungültiger Wert. Bitte eine Zahl zwischen 1 und 99 eingeben.")
                    .setEphemeral(true).queue();
            return;
        }

        if (newLimit < 1 || newLimit > 99) {
            event.reply("❌ Das Limit muss zwischen 1 und 99 liegen.").setEphemeral(true).queue();
            return;
        }

        service.setUserLimit(channel, newLimit);
        event.reply("✅ Nutzer-Limit auf **" + newLimit + "** gesetzt.").setEphemeral(true).queue();
    }

    // ── Action helpers ────────────────────────────────────────────────────

    private void handleLock(ButtonInteractionEvent event, Guild guild,
                            VoiceChannel channel, TempChannelData data) {
        service.lockChannel(guild, channel, data);
        event.reply("🔒 Kanal wurde gesperrt.").setEphemeral(true).queue();
    }

    private void handleUnlock(ButtonInteractionEvent event, Guild guild,
                               VoiceChannel channel, TempChannelData data) {
        service.unlockChannel(guild, channel, data);
        event.reply("🔓 Kanal wurde entsperrt.").setEphemeral(true).queue();
    }

    private void handleTransferPrompt(ButtonInteractionEvent event,
                                       Guild guild, VoiceChannel channel) {
        long callerId = event.getUser().getIdLong();
        List<Member> eligibleMembers = channel.getMembers().stream()
                .filter(m -> m.getIdLong() != callerId)
                .collect(Collectors.toList());

        if (eligibleMembers.isEmpty()) {
            event.reply("❌ Keine weiteren Mitglieder im Kanal.").setEphemeral(true).queue();
            return;
        }

        var menuBuilder = StringSelectMenu
                .create("tc:select:transfer:" + channel.getId())
                .setPlaceholder("Neuen Kanal-Ersteller auswählen");

        for (Member m : eligibleMembers) {
            menuBuilder.addOption(m.getEffectiveName(), m.getId());
        }

        event.reply("👑 Wähle den neuen Kanalersteller:")
                .addComponents(ActionRow.of(menuBuilder.build()))
                .setEphemeral(true)
                .queue();
    }

    private void handleKickPrompt(ButtonInteractionEvent event, Guild guild,
                                   VoiceChannel channel, TempChannelData data) {
        long callerId = event.getUser().getIdLong();
        List<Member> others = channel.getMembers().stream()
                .filter(m -> m.getIdLong() != callerId)
                .collect(Collectors.toList());

        if (others.isEmpty()) {
            event.reply("❌ Keine anderen Mitglieder im Kanal.").setEphemeral(true).queue();
            return;
        }

        var menuBuilder = StringSelectMenu
                .create("tc:select:kick:" + channel.getId())
                .setPlaceholder("Nutzer zum Kicken auswählen");

        for (Member m : others) {
            menuBuilder.addOption(m.getEffectiveName(), m.getId());
        }

        event.reply("👢 Wen möchtest du aus dem Kanal entfernen?")
                .addComponents(ActionRow.of(menuBuilder.build()))
                .setEphemeral(true)
                .queue();
    }

    private void handleLimitPrompt(ButtonInteractionEvent event, long channelId) {
        TextInput input = TextInput.create("new_limit", "Neues Nutzer-Limit (1–99)", TextInputStyle.SHORT)
                .setPlaceholder("z. B. 4")
                .setMinLength(1)
                .setMaxLength(2)
                .setRequired(true)
                .build();

        Modal modal = Modal.create("tc:modal:limit:" + channelId, "Nutzer-Limit ändern")
                .addComponents(ActionRow.of(input))
                .build();

        event.replyModal(modal).queue();
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            logger.warn("Could not parse ID from interaction component: '{}'", s);
            return -1;
        }
    }
}
