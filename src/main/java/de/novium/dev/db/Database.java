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
    private static final String DB_URL = "jdbc:sqlite:tempchannels.db";

    private final Connection connection;

    public Database() {
        connection = connect();
        createTableIfNotExists();
    }

    private Connection connect() {
        try {
            Connection conn = DriverManager.getConnection(DB_URL);
            logger.info("Connected to SQLite database");
            return conn;
        } catch (SQLException e) {
            logger.error("Failed to connect to SQLite database", e);
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
                    panel_msg_id    TEXT
                );
                """;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            logger.error("Failed to create temp_channels table", e);
            throw new RuntimeException("Table creation failed", e);
        }
    }

    // ── Write operations ──────────────────────────────────────────────────

    public synchronized void save(TempChannelData data) {
        String sql = """
                INSERT OR REPLACE INTO temp_channels
                    (channel_id, creator_id, type_name, activated, locked, whitelist, panel_msg_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, data.getChannelId());
            ps.setLong(2, data.getCreatorId());
            ps.setString(3, data.getType().name());
            ps.setInt(4, data.isActivated() ? 1 : 0);
            ps.setInt(5, data.isLocked() ? 1 : 0);
            ps.setString(6, data.getWhitelistAsString());
            ps.setString(7, data.getPanelMessageId());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save temp channel {}", data.getChannelId(), e);
        }
    }

    public synchronized void delete(long channelId) {
        String sql = "DELETE FROM temp_channels WHERE channel_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, channelId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete temp channel {} from database", channelId, e);
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
                    logger.warn("Unknown channel type '{}' for channel {}, skipping", typeName, channelId);
                    continue;
                }

                TempChannelData data = new TempChannelData(channelId, type);
                data.setCreatorId(rs.getLong("creator_id"));
                data.setLocked(rs.getInt("locked") == 1);
                data.loadWhitelistFromString(rs.getString("whitelist"));
                data.setPanelMessageId(rs.getString("panel_msg_id"));

                if (rs.getInt("activated") == 1) {
                    // Restore activated state without triggering the compareAndSet logic
                    data.activate(data.getCreatorId());
                }

                result.add(data);
            }
        } catch (SQLException e) {
            logger.error("Failed to load temp channels from database", e);
        }

        logger.info("Loaded {} temp channel(s) from database", result.size());
        return result;
    }
}
