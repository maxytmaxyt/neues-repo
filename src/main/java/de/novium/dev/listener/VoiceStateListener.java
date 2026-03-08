package de.novium.dev.listener;

import de.novium.dev.tempchannel.TempChannelService;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/**
 * Listens for voice state changes and forwards them to {@link TempChannelService}.
 *
 * This listener intentionally does as little work as possible on the JDA
 * event thread – it only validates the event type and delegates everything
 * else to the service's own executor pool.
 */
public class VoiceStateListener extends ListenerAdapter {

    private final TempChannelService service;

    public VoiceStateListener(TempChannelService service) {
        this.service = service;
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        AudioChannelUnion joined = event.getChannelJoined();
        AudioChannelUnion left   = event.getChannelLeft();

        // A member joined a voice channel
        if (joined != null && joined.getType() == ChannelType.VOICE) {
            service.handleJoin(event.getMember(), joined.asVoiceChannel());
        }

        // A member left a voice channel
        if (left != null && left.getType() == ChannelType.VOICE) {
            service.handleLeave(event.getMember(), left.asVoiceChannel());
        }
    }
}
