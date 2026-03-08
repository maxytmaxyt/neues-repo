package de.novium.dev;

import de.novium.dev.config.BotConfig;
import de.novium.dev.db.Database;
import de.novium.dev.listener.InteractionListener;
import de.novium.dev.listener.VoiceStateListener;
import de.novium.dev.panel.PanelManager;
import de.novium.dev.tempchannel.TempChannelService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

public class BotMain {

    private static final Logger logger = LoggerFactory.getLogger(BotMain.class);

    public static void main(String[] args) {
        logger.info("Starting Discord TempChannel Bot...");

        // ── Configuration ────────────────────────────────────────────────
        BotConfig config;
        try {
            config = BotConfig.load("bot.properties");
        } catch (Exception e) {
            logger.error("Failed to load configuration: {}", e.getMessage());
            System.exit(1);
            return;
        }

        // ── Database ─────────────────────────────────────────────────────
        Database database;
        try {
            database = new Database();
        } catch (Exception e) {
            logger.error("Failed to initialise database: {}", e.getMessage());
            System.exit(1);
            return;
        }

        // ── Services ──────────────────────────────────────────────────────
        PanelManager panelManager = new PanelManager(config.getPanelChannelId());

        // JDA must be built before TempChannelService (we pass it along)
        JDA jda;
        try {
            jda = JDABuilder.createDefault(config.getToken())
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.watching("Voice Channels"))
                    .enableIntents(EnumSet.of(
                            GatewayIntent.GUILD_VOICE_STATES,
                            GatewayIntent.GUILD_MEMBERS,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT
                    ))
                    .build();
        } catch (Exception e) {
            logger.error("Failed to build JDA: {}", e.getMessage());
            System.exit(1);
            return;
        }

        TempChannelService tempChannelService = new TempChannelService(jda, config, database, panelManager);

        // ── Listeners ─────────────────────────────────────────────────────
        jda.addEventListener(new VoiceStateListener(tempChannelService));
        jda.addEventListener(new InteractionListener(tempChannelService));

        // ── Wait for JDA ready, then initialise channels ──────────────────
        try {
            jda.awaitReady();
        } catch (InterruptedException e) {
            logger.error("Startup interrupted", e);
            Thread.currentThread().interrupt();
            return;
        }

        Guild guild = jda.getGuildById(config.getGuildId());
        if (guild == null) {
            logger.error("Guild {} not found! Check guild.id in bot.properties", config.getGuildId());
            jda.shutdown();
            return;
        }

        tempChannelService.initialize(guild);

        jda.getPresence().setActivity(Activity.watching("Temp Channels 🔊"));
        logger.info("Bot is ready — managing temp channels in guild '{}'", guild.getName());

        // ── Graceful shutdown hook ─────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            tempChannelService.shutdown();
            jda.shutdown();
        }, "shutdown-hook"));
    }
}
