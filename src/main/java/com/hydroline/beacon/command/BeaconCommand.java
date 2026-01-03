package com.hydroline.beacon.command;

import com.hydroline.beacon.BeaconPlugin;
import com.hydroline.beacon.config.PluginConfig;
import com.hydroline.beacon.provider.channel.BeaconProviderClient;
import com.hydroline.beacon.provider.channel.BeaconProviderClient.StatusSnapshot;
import com.hydroline.beacon.socket.SocketServerManager;
import com.hydroline.beacon.task.AdvancementsAndStatsScanner;
import com.hydroline.beacon.task.MtrLogsScanner;
import com.hydroline.beacon.task.MtrWorldScanner;
import com.hydroline.beacon.task.NbtIdentityScanner;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BeaconCommand implements CommandExecutor {

    private static final int QUERY_MAX_ROWS = 5;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String PERMISSION = "hydroline.beacon.admin";

    private final BeaconPlugin plugin;
    private final TranslationManager translations;

    public BeaconCommand(BeaconPlugin plugin) {
        this.plugin = plugin;
        this.translations = new TranslationManager(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {
        String locale = resolveLocale(sender);
        if (!sender.hasPermission(PERMISSION)) {
            send(sender, locale, "commands.beacon.permission.denied");
            return true;
        }
        if (args == null || args.length == 0) {
            showHelp(sender, locale);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list":
                handleList(sender, locale);
                break;
            case "provider":
                handleProvider(sender, locale, args);
                break;
            case "sync":
                handleSync(sender, locale, args);
                break;
            case "query":
                handleQuery(sender, locale, args);
                break;
            case "stats":
                handleStats(sender, locale);
                break;
            case "info":
                handleInfo(sender, locale);
                break;
            case "help":
                showHelp(sender, locale);
                break;
            default:
                send(sender, locale, "commands.beacon.unknown", args[0]);
                showHelp(sender, locale);
        }
        return true;
    }

    private void showHelp(CommandSender sender, String locale) {
        send(sender, locale, "commands.beacon.help.header");
        send(sender, locale, "commands.beacon.help.list");
        send(sender, locale, "commands.beacon.help.provider");
        send(sender, locale, "commands.beacon.help.sync");
        send(sender, locale, "commands.beacon.help.sync_nbt");
        send(sender, locale, "commands.beacon.help.sync_scans");
        send(sender, locale, "commands.beacon.help.query");
        send(sender, locale, "commands.beacon.help.stats");
        send(sender, locale, "commands.beacon.help.info");
    }

    private void handleList(CommandSender sender, String locale) {
        SocketServerManager manager = plugin.getSocketServerManager();
        if (manager == null || !manager.isServerRunning()) {
            send(sender, locale, "commands.beacon.list.offline");
            return;
        }
        List<String> summaries = manager.getConnectedClientSummaries();
        if (summaries.isEmpty()) {
            send(sender, locale, "commands.beacon.list.empty");
            return;
        }
        send(sender, locale, "commands.beacon.list.header", summaries.size());
        for (int i = 0; i < summaries.size(); i++) {
            send(sender, locale, "commands.beacon.list.entry", i + 1, summaries.get(i));
        }
    }

    private void handleProvider(CommandSender sender, String locale, String[] args) {
        if (args.length < 2) {
            send(sender, locale, "commands.beacon.provider.invalid");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status":
                handleProviderStatus(sender, locale);
                break;
            case "connect":
                handleProviderConnect(sender, locale, args);
                break;
            case "disconnect":
                handleProviderDisconnect(sender, locale);
                break;
            default:
                send(sender, locale, "commands.beacon.provider.invalid");
        }
    }

    private void handleProviderStatus(CommandSender sender, String locale) {
        BeaconProviderClient client = plugin.getBeaconProviderClient();
        if (client == null) {
            send(sender, locale, "commands.beacon.provider.status.unavailable");
            return;
        }
        StatusSnapshot snapshot = client.getStatusSnapshot();
        if (!snapshot.gatewayEnabled()) {
            send(sender, locale, "commands.beacon.provider.status.gateway_disabled");
            return;
        }
        String stateKey = snapshot.isConnected()
                ? "commands.beacon.provider.status.state.connected"
                : "commands.beacon.provider.status.state.disconnected";
        String stateLabel = translations.get(locale, stateKey);
        send(sender, locale, "commands.beacon.provider.status", stateLabel);
        send(sender, locale, "commands.beacon.provider.details.connection",
                snapshot.getConnectionId() != null ? snapshot.getConnectionId() : "-");
        if (snapshot.getHeartbeatSeconds() > 0) {
            send(sender, locale, "commands.beacon.provider.details.heartbeat", snapshot.getHeartbeatSeconds());
        }
        if (snapshot.getLastConnectedAtMillis() > 0) {
            send(sender, locale,
                    "commands.beacon.provider.details.last_connected",
                    formatTimestamp(snapshot.getLastConnectedAtMillis()));
        }
        send(sender, locale, "commands.beacon.provider.details.pending", snapshot.getPendingRequests());
        send(sender, locale, "commands.beacon.provider.details.reconnect", snapshot.getReconnectDelayMillis());
        send(sender, locale, "commands.beacon.provider.details.version",
                snapshot.getModVersion() != null ? snapshot.getModVersion() : "-");
    }

    private void handleProviderConnect(CommandSender sender, String locale, String[] args) {
        BeaconProviderClient client = plugin.getBeaconProviderClient();
        if (client == null) {
            send(sender, locale, "commands.beacon.provider.status.unavailable");
            return;
        }
        if (client.isStarted()) {
            send(sender, locale, "commands.beacon.provider.connect.already");
            return;
        }
        Integer port = null;
        if (args.length >= 3) {
            try {
                port = Integer.parseInt(args[2]);
                if (port <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ex) {
                send(sender, locale, "commands.beacon.provider.connect.invalid_port", args[2]);
                return;
            }
        }
        String error = client.requestManualConnect(port);
        if (error != null) {
            plugin.getLogger().warning("Manual Beacon Provider connect failed: " + error);
            send(sender, locale, "commands.beacon.provider.connect.failed", error);
            return;
        }
        String portHint = "";
        if (port != null) {
            portHint = translations.get(locale, "commands.beacon.provider.connect.port_hint", port);
        }
        send(sender, locale, "commands.beacon.provider.connect.started", portHint);
    }

    private void handleProviderDisconnect(CommandSender sender, String locale) {
        BeaconProviderClient client = plugin.getBeaconProviderClient();
        if (client == null) {
            send(sender, locale, "commands.beacon.provider.status.unavailable");
            return;
        }
        String error = client.requestManualDisconnect();
        if (error != null) {
            plugin.getLogger().warning("Manual Beacon Provider disconnect failed: " + error);
            send(sender, locale, "commands.beacon.provider.disconnect.failed", error);
            return;
        }
        send(sender, locale, "commands.beacon.provider.disconnect.success");
    }

    private void handleSync(CommandSender sender, String locale, String[] args) {
        if (args.length < 2) {
            send(sender, locale, "commands.beacon.sync.usage");
            return;
        }
        String target = args[1].toLowerCase(Locale.ROOT);
        switch (target) {
            case "nbt":
                runNbtSync(sender, locale);
                break;
            case "scans":
            case "all":
                runScansSync(sender, locale);
                break;
            default:
                send(sender, locale, "commands.beacon.sync.invalid", target);
        }
    }

    private void runNbtSync(CommandSender sender, String locale) {
        send(sender, locale, "commands.beacon.sync.nbt.started");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                new NbtIdentityScanner(plugin).scanOnce();
                sendAsync(sender, locale, "commands.beacon.sync.nbt.success");
            } catch (Throwable ex) {
                sendAsync(sender, locale, "commands.beacon.sync.nbt.failed", ex.getMessage());
            }
        });
    }

    private void runScansSync(CommandSender sender, String locale) {
        send(sender, locale, "commands.beacon.sync.scans.started");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                new AdvancementsAndStatsScanner(plugin).scanOnce();
                new MtrLogsScanner(plugin).scanOnce();
                new MtrWorldScanner(plugin).scanOnce();
                sendAsync(sender, locale, "commands.beacon.sync.scans.success");
            } catch (Throwable ex) {
                sendAsync(sender, locale, "commands.beacon.sync.scans.failed", ex.getMessage());
            }
        });
    }

    private void handleQuery(CommandSender sender, String locale, String[] args) {
        if (args.length < 2) {
            send(sender, locale, "commands.beacon.query.noargs");
            return;
        }
        String sql = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (sql.isEmpty() || !sql.toLowerCase(Locale.ROOT).startsWith("select") || sql.contains(";")) {
            send(sender, locale, "commands.beacon.query.invalid");
            return;
        }
        send(sender, locale, "commands.beacon.query.start", sql);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> executeQuery(sender, locale, sql));
    }

    private void executeQuery(CommandSender sender, String locale, String sql) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setMaxRows(QUERY_MAX_ROWS + 1);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                if (columnCount == 0) {
                    sendAsync(sender, locale, "commands.beacon.query.empty");
                    return;
                }
                StringBuilder header = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    header.append(meta.getColumnLabel(i));
                    if (i < columnCount) {
                        header.append(" | ");
                    }
                }
                boolean hasRows = false;
                int row = 0;
                boolean truncated = false;
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    if (row >= QUERY_MAX_ROWS) {
                        truncated = true;
                        break;
                    }
                    StringBuilder rowBuilder = new StringBuilder();
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        rowBuilder.append(value != null ? value.toString() : "null");
                        if (i < columnCount) {
                            rowBuilder.append(" | ");
                        }
                    }
                    rows.add(rowBuilder.toString());
                    hasRows = true;
                    row++;
                }
                if (!hasRows) {
                    sendAsync(sender, locale, "commands.beacon.query.empty");
                    return;
                }
                sendAsync(sender, locale, "commands.beacon.query.columns", header.toString());
                for (String rowText : rows) {
                    sendAsync(sender, locale, "commands.beacon.query.row", rowText);
                }
                if (truncated) {
                    sendAsync(sender, locale, "commands.beacon.query.truncated", QUERY_MAX_ROWS);
                }
            }
        } catch (SQLException ex) {
            sendAsync(sender, locale, "commands.beacon.query.error", ex.getMessage());
        }
    }

    private void handleStats(CommandSender sender, String locale) {
        send(sender, locale, "commands.beacon.stats.header");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                Map<String, String> metrics = new LinkedHashMap<>();
                metrics.put("commands.beacon.stats.label.player_sessions", String.valueOf(queryLong(conn, "SELECT COUNT(*) FROM player_sessions")));
                metrics.put("commands.beacon.stats.label.unique_players", String.valueOf(queryLong(conn, "SELECT COUNT(DISTINCT player_uuid) FROM player_sessions")));
                metrics.put("commands.beacon.stats.label.player_advancements", String.valueOf(queryLong(conn, "SELECT COUNT(*) FROM player_advancements")));
                metrics.put("commands.beacon.stats.label.player_stats", String.valueOf(queryLong(conn, "SELECT COUNT(*) FROM player_stats")));
                metrics.put("commands.beacon.stats.label.player_nbt_cache", String.valueOf(queryLong(conn, "SELECT COUNT(*) FROM player_nbt_cache")));
                metrics.put("commands.beacon.stats.label.mtr_logs", String.valueOf(queryLong(conn, "SELECT COUNT(*) FROM mtr_logs")));
                long lastSession = queryLong(conn, "SELECT MAX(occurred_at) FROM player_sessions");
                metrics.put("commands.beacon.stats.label.last_session", formatTimestampValue(locale, lastSession));
                long lastNbt = queryLong(conn, "SELECT MAX(cached_at) FROM player_nbt_cache");
                metrics.put("commands.beacon.stats.label.last_nbt_cache", formatTimestampValue(locale, lastNbt));
                long lastAdvancement = queryLong(conn, "SELECT MAX(last_updated) FROM player_advancements");
                metrics.put("commands.beacon.stats.label.last_advancement", formatTimestampValue(locale, lastAdvancement));
                metrics.forEach((labelKey, value) ->
                        sendAsync(sender, locale, "commands.beacon.stats.entry",
                                translations.get(locale, labelKey), value));
            } catch (SQLException ex) {
                sendAsync(sender, locale, "commands.beacon.stats.error", ex.getMessage());
            }
        });
    }

    private void handleInfo(CommandSender sender, String locale) {
        send(sender, locale, "commands.beacon.info.header");
        PluginConfig config = plugin.getConfigManager().getCurrentConfig();
        send(sender, locale, "commands.beacon.info.entry",
                translations.get(locale, "commands.beacon.info.label.port"),
                String.valueOf(config.getPort()));
        send(sender, locale, "commands.beacon.info.entry",
                translations.get(locale, "commands.beacon.info.label.interval"),
                String.valueOf(config.getIntervalTimeTicks()));
        send(sender, locale, "commands.beacon.info.entry",
                translations.get(locale, "commands.beacon.info.label.version"),
                String.valueOf(config.getVersion()));
        send(sender, locale, "commands.beacon.info.entry",
                translations.get(locale, "commands.beacon.info.label.nbt_ttl"),
                String.valueOf(config.getNbtCacheTtlMinutes()) + " min");
        send(sender, locale, "commands.beacon.info.entry",
                translations.get(locale, "commands.beacon.info.label.mtr_world"),
                translations.get(locale, config.isMtrWorldScanEnabled()
                        ? "commands.beacon.info.value.enabled"
                        : "commands.beacon.info.value.disabled"));
        BeaconProviderClient client = plugin.getBeaconProviderClient();
        boolean providerEnabled = client != null && client.getStatusSnapshot().gatewayEnabled();
        send(sender, locale, "commands.beacon.info.entry",
                translations.get(locale, "commands.beacon.info.label.provider"),
                translations.get(locale, providerEnabled
                        ? "commands.beacon.info.value.enabled"
                        : "commands.beacon.info.value.disabled"));
    }

    private String formatTimestamp(long epochMillis) {
        return TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private String formatTimestampValue(String locale, long epochMillis) {
        if (epochMillis <= 0) {
            return translations.get(locale, "commands.beacon.stats.value.never");
        }
        return formatTimestamp(epochMillis);
    }

    private long queryLong(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        }
    }

    private void send(CommandSender sender, String locale, String key, Object... params) {
        sender.sendMessage(colorize(translations.get(locale, key, params)));
    }

    private void sendAsync(CommandSender sender, String locale, String key, Object... params) {
        Bukkit.getScheduler().runTask(plugin, () -> send(sender, locale, key, params));
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private String resolveLocale(CommandSender sender) {
        if (sender instanceof Player) {
            String locale = ((Player) sender).getLocale();
            if (locale != null && !locale.trim().isEmpty()) {
                return locale;
            }
        }
        PluginConfig cfg = plugin.getConfigManager().getCurrentConfig();
        if (cfg != null) {
            String defaultLang = cfg.getDefaultLanguage();
            if (defaultLang != null && !defaultLang.trim().isEmpty()) {
                return defaultLang;
            }
        }
        return "zh_cn";
    }
}
