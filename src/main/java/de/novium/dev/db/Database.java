package de.novium.dev.db;

import de.novium.dev.tempchannel.TempChannelData;
import de.novium.dev.tempchannel.TempChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite persistence layer for temporary channels.
 *
 * The channel state is primarily managed in-memory; the database acts
 * as a recovery source so the bot can restore its state after a restart.
 */
public class Database {

    private static final Logger logger = LoggerFactory.getLogger(Database.class);
    private static final String DB_URL = "jdbc:sqlite:database.db";

    private final Connection connection;

    public Database() {
        connection = connect();
        createTableIfNotExists();
        migrateSchema();
    }

    private Connection connect() {
        try {
            Connection conn = DriverManager.getConnection(DB_URL);
            logger.info("Verbunden mit SQLite-Datenbank");
            return conn;
        } catch (SQLException e) {
            logger.error("Datenbankverbindung fehlgeschlagen", e);
            throw new RuntimeException("Database connection failed", e);
        }
    }

    private void createTableIfNotExists() {
        String sql = """
                CREATE TABLE IF NOT EXISTS temp_channels (
                    channel_id      INTEGER PRIMARY KEY,
                    creator_id      INTEGER NOT NULL DEFAULT 0,
                    type_name       TEXT    NOT NULL,
                    activated       INTEGER NOT NULL DEFAULT 0,
                    locked          INTEGER NOT NULL DEFAULT 0,
                    whitelist       TEXT,
                    panel_msg_id    TEXT,
                    channel_number  INTEGER NOT NULL DEFAULT 0
                );
                """;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            logger.error("Tabelle konnte nicht erstellt werden", e);
            throw new RuntimeException("Table creation failed", e);
        }
    }

    /**
     * Adds the {@code channel_number} column to existing databases that were
     * created before this column was introduced. SQLite doesn't support
     * IF NOT EXISTS for ALTER TABLE, so we catch the error gracefully.
     */
    private void migrateSchema() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER TABLE temp_channels ADD COLUMN channel_number INTEGER NOT NULL DEFAULT 0");
            logger.info("Schema-Migration: Spalte 'channel_number' hinzugefügt");
        } catch (SQLException e) {
            // Column already exists – this is the normal case after first migration
            logger.debug("Schema-Migration: 'channel_number' bereits vorhanden, übersprungen");
        }
    }

    // ── Write operations ──────────────────────────────────────────────────

    public synchronized void save(TempChannelData data) {
        String sql = """
                INSERT OR REPLACE INTO temp_channels
                    (channel_id, creator_id, type_name, activated, locked, whitelist, panel_msg_id, channel_number)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1,   data.getChannelId());
            ps.setLong(2,   data.getCreatorId());
            ps.setString(3, data.getType().name());
            ps.setInt(4,    data.isActivated() ? 1 : 0);
            ps.setInt(5,    data.isLocked() ? 1 : 0);
            ps.setString(6, data.getWhitelistAsString());
            ps.setString(7, data.getPanelMessageId());
            ps.setInt(8,    data.getChannelNumber());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Fehler beim Speichern von Kanal {}", data.getChannelId(), e);
        }
    }

    public synchronized void delete(long channelId) {
        String sql = "DELETE FROM temp_channels WHERE channel_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, channelId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Fehler beim Löschen von Kanal {} aus der Datenbank", channelId, e);
        }
    }

    // ── Read operations ───────────────────────────────────────────────────

    /**
     * Loads all persisted temp channels. Called once on startup.
     * Channels whose Discord counterparts no longer exist are filtered
     * out by the caller.
     */
    public List<TempChannelData> loadAll() {
        List<TempChannelData> result = new ArrayList<>();
        String sql = "SELECT * FROM temp_channels";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                long channelId = rs.getLong("channel_id");
                String typeName = rs.getString("type_name");
                TempChannelType type = TempChannelType.fromName(typeName);

                if (type == null) {
                    logger.warn("Unbekannter Channel-Typ '{}' für Kanal {}, wird übersprungen", typeName, channelId);
                    continue;
                }

                TempChannelData data = new TempChannelData(channelId, type);
                data.setCreatorId(rs.getLong("creator_id"));
                data.setLocked(rs.getInt("locked") == 1);
                data.loadWhitelistFromString(rs.getString("whitelist"));
                data.setPanelMessageId(rs.getString("panel_msg_id"));
                data.setChannelNumber(rs.getInt("channel_number"));

                if (rs.getInt("activated") == 1) {
                    data.activate(data.getCreatorId());
                }

                result.add(data);
            }
        } catch (SQLException e) {
            logger.error("Fehler beim Laden der Kanäle aus der Datenbank", e);
        }

        logger.info("{} Kanal/Kanäle aus der Datenbank geladen", result.size());
        return result;
    }
}
