package com.hydroline.beacon;

import com.hydroline.beacon.config.ConfigManager;
import com.hydroline.beacon.config.PluginConfig;
import com.hydroline.beacon.listener.PlayerSessionListener;
import com.hydroline.beacon.provider.actions.BeaconProviderActions;
import com.hydroline.beacon.provider.actions.dto.BeaconPingResponse;
import com.hydroline.beacon.provider.channel.BeaconProviderClient;
import com.hydroline.beacon.socket.SocketServerManager;
import com.hydroline.beacon.storage.DatabaseManager;
import com.hydroline.beacon.task.ScanScheduler;
import com.hydroline.beacon.world.WorldFileAccess;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.SQLException;

public class BeaconPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private ScanScheduler scanScheduler;
    private WorldFileAccess worldFileAccess;
    private SocketServerManager socketServerManager;
    private BeaconProviderClient beaconProviderClient;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        PluginConfig cfg = configManager.getCurrentConfig();
        getLogger().info("Hydroline Beacon enabled with port=" + cfg.getPort()
                + ", interval_time_ticks=" + cfg.getIntervalTimeTicks()
                + ", version=" + cfg.getVersion());

        this.databaseManager = new DatabaseManager(this);
        this.worldFileAccess = new WorldFileAccess(Bukkit.getWorlds());
        Bukkit.getPluginManager().registerEvents(new PlayerSessionListener(this), this);

        this.beaconProviderClient = new BeaconProviderClient(this);
        this.beaconProviderClient.start();
        scheduleBeaconProviderStartupPing();

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                databaseManager.initialize();
                getLogger().info("SQLite database initialized successfully.");

                // Backfill: close any sessions whose last record is JOIN (unclean shutdown previously)
                try {
                    int fixed = closeOpenSessions(System.currentTimeMillis());
                    if (fixed > 0) {
                        getLogger().info("Backfilled ABNORMAL_QUIT events for " + fixed + " player(s) with unclosed sessions.");
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to backfill unclosed sessions: " + e.getMessage());
                }

                // 初始化完成后启动定时异步扫描任务
                this.scanScheduler = new ScanScheduler(this);
                this.scanScheduler.start();

                this.socketServerManager = new SocketServerManager(this);
                this.socketServerManager.start();
            } catch (SQLException e) {
                getLogger().severe("Failed to initialize SQLite database: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDisable() {
        if (this.scanScheduler != null) {
            this.scanScheduler.stop();
        }
        if (this.socketServerManager != null) {
            this.socketServerManager.stop();
        }
        if (this.beaconProviderClient != null) {
            this.beaconProviderClient.stop();
        }
        // On shutdown, ensure any players whose last event is JOIN receive an ABNORMAL_QUIT record
        try {
            int fixed = closeOpenSessions(System.currentTimeMillis());
            if (fixed > 0) {
                getLogger().info("Backfilled ABNORMAL_QUIT events on shutdown for " + fixed + " player(s).");
            }
        } catch (Exception e) {
            getLogger().warning("Failed to backfill sessions on shutdown: " + e.getMessage());
        }
        getLogger().info("Hydroline Beacon disabled!");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public WorldFileAccess getWorldFileAccess() {
        return worldFileAccess;
    }

    public BeaconProviderClient getBeaconProviderClient() {
        return beaconProviderClient;
    }

    private void scheduleBeaconProviderStartupPing() {
        if (this.beaconProviderClient == null) {
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                if (beaconProviderClient == null || !beaconProviderClient.isStarted()) {
                    return;
                }
                cancel();
                beaconProviderClient
                        .sendAction(BeaconProviderActions.ping("startup"))
                        .whenComplete((response, throwable) -> {
                            if (throwable != null) {
                                getLogger().warning("Beacon Provider channel ping failed: " + throwable.getMessage());
                                return;
                            }
                            BeaconPingResponse payload = response.getPayload();
                            if (response.isOk() && payload != null) {
                                getLogger().info("Beacon Provider channel ready, latency=" + payload.getLatencyMs() + "ms");
                            } else {
                                getLogger().warning("Beacon Provider channel ping returned " + response.getResult() + ": " + response.getMessage());
                            }
                        });
            }
        }.runTaskTimer(this, 60L, 100L);
    }

    /**
     * Close any "open" sessions by inserting a synthetic QUIT for players whose latest event is JOIN.
     * Returns number of players affected.
     */
    private int closeOpenSessions(long occurredAt) throws SQLException {
        if (this.databaseManager == null) return 0;
        int affected = 0;
        try (java.sql.Connection conn = this.databaseManager.getConnection()) {
            String sql = "SELECT s.player_uuid, s.player_name, s.world_name, s.dimension_key, s.x, s.y, s.z " +
                    "FROM player_sessions s " +
                    "JOIN (SELECT player_uuid, MAX(id) AS last_id FROM player_sessions GROUP BY player_uuid) t " +
                    "ON t.last_id = s.id WHERE s.event_type = 'JOIN'";
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                 java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String playerUuid = rs.getString(1);
                    String playerName = rs.getString(2);
                    String worldName = rs.getString(3);
                    String dimensionKey = rs.getString(4);
                    Double x = rs.getObject(5) != null ? rs.getDouble(5) : null;
                    Double y = rs.getObject(6) != null ? rs.getDouble(6) : null;
                    Double z = rs.getObject(7) != null ? rs.getDouble(7) : null;

                        try (java.sql.PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO player_sessions (event_type, occurred_at, player_uuid, player_name, player_ip, world_name, dimension_key, x, y, z) " +
                                "VALUES ('ABNORMAL_QUIT', ?, ?, ?, NULL, ?, ?, ?, ?, ?)")) {
                        ins.setLong(1, occurredAt);
                        ins.setString(2, playerUuid);
                        ins.setString(3, playerName);
                        ins.setString(4, worldName);
                        ins.setString(5, dimensionKey);
                        if (x != null) ins.setDouble(6, x); else ins.setNull(6, java.sql.Types.REAL);
                        if (y != null) ins.setDouble(7, y); else ins.setNull(7, java.sql.Types.REAL);
                        if (z != null) ins.setDouble(8, z); else ins.setNull(8, java.sql.Types.REAL);
                        ins.executeUpdate();
                        affected++;
                    }
                }
            }
        }
        return affected;
    }
}
