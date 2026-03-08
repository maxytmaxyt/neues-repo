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
        logger.info("Bot wird gestartet...");

        BotConfig config;
        try {
            config = BotConfig.load("bot.properties");
        } catch (Exception e) {
            logger.error("Konfiguration konnte nicht geladen werden: {}", e.getMessage());
            System.exit(1);
            return;
        }

        Database database;
        try {
            database = new Database();
        } catch (Exception e) {
            logger.error("Datenbankverbindung fehlgeschlagen: {}", e.getMessage());
            System.exit(1);
            return;
        }

        PanelManager panelManager = new PanelManager(config.getPanelChannelId());

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
            logger.error("JDA konnte nicht gestartet werden: {}", e.getMessage());
            System.exit(1);
            return;
        }

        TempChannelService service = new TempChannelService(jda, config, database, panelManager);
        jda.addEventListener(new VoiceStateListener(service));
        jda.addEventListener(new InteractionListener(service));

        try {
            jda.awaitReady();
        } catch (InterruptedException e) {
            logger.error("Startup unterbrochen", e);
            Thread.currentThread().interrupt();
            return;
        }

        Guild guild = jda.getGuildById(config.getGuildId());
        if (guild == null) {
            logger.error("Guild {} nicht gefunden! guild.id in bot.properties prüfen", config.getGuildId());
            jda.shutdown();
            return;
        }

        service.initialize(guild);
        jda.getPresence().setActivity(Activity.watching("Temp Channels 🔊"));
        logger.info("Bot ist bereit auf Server '{}'", guild.getName());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Bot wird beendet...");
            service.shutdown();
            jda.shutdown();
        }, "shutdown-hook"));
    }
}
