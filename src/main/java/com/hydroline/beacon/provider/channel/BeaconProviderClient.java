package com.hydroline.beacon.provider.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hydroline.beacon.BeaconPlugin;
import com.hydroline.beacon.gateway.NettyGatewayConfig;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Netty Gateway client that talks to the Forge/Fabric provider over TCP instead of Plugin Messaging.
 */
public final class BeaconProviderClient {
    private static final int PROTOCOL_VERSION = 1;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000L;
    private static final Path GATEWAY_CONFIG_PATH = Paths.get("config", "beacon-provider", "beacon-provider.json");

    private final BeaconPlugin plugin;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, PendingRequest<?>> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "beacon-gateway-client");
        t.setDaemon(true);
        return t;
    });
    private final Object writeLock = new Object();
    private final AtomicLong pingSeq = new AtomicLong(1);

    private volatile boolean started;
    private volatile boolean stopRequested;
    private volatile NettyGatewayConfig gatewayConfig;
    private volatile Socket socket;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private volatile Thread readerThread;
    private volatile GatewaySession session;
    private volatile CompletableFuture<Void> handshakeFuture;
    private volatile ScheduledFuture<?> handshakeTimeoutFuture;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile long reconnectDelayMs = 1000L;
    private volatile String peerVersion = "unknown";
    private volatile long lastConnectedAtMillis;

    public BeaconProviderClient(BeaconPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        this.gatewayConfig = NettyGatewayConfig.load(GATEWAY_CONFIG_PATH, plugin.getLogger());
        if (gatewayConfig == null || !gatewayConfig.isEnabled()) {
            plugin.getLogger().warning("Beacon Provider gateway disabled (missing config or listenPort <= 0)");
            return;
        }
        this.started = true;
        this.stopRequested = false;
        submitConnect(0L);
    }

    public synchronized void stop() {
        this.stopRequested = true;
        this.started = false;
        cancelHeartbeat();
        cancelHandshakeTimeout();
        closeSocket();
        executor.shutdownNow();
        failAllPending(new IllegalStateException("Beacon Provider client stopped"));
    }

    public synchronized String requestManualConnect(Integer portOverride) {
        if (portOverride != null && portOverride <= 0) {
            return "port must be a positive number";
        }
        if (stopRequested) {
            stopRequested = false;
        }
        NettyGatewayConfig config = NettyGatewayConfig.load(GATEWAY_CONFIG_PATH, plugin.getLogger());
        if (config == null) {
            return "gateway config missing: " + GATEWAY_CONFIG_PATH;
        }
        if (!config.isEnabled()) {
            return "gateway config is disabled or missing auth token";
        }
        if (portOverride != null) {
            if (portOverride > 0 && portOverride <= 65535) {
                config = config.withListenPort(portOverride);
            } else {
                return "port must be between 1 and 65535";
            }
        }
        this.gatewayConfig = config;
        if (!started) {
            started = true;
        }
        submitConnect(0L);
        return null;
    }

    public synchronized String requestManualDisconnect() {
        if (!started) {
            return "gateway client is not running";
        }
        stopRequested = true;
        closeSocket();
        failAllPending(new IOException("Manual disconnect requested"));
        started = false;
        return null;
    }

    public boolean isStarted() {
        return started && session != null && socket != null;
    }

    public StatusSnapshot getStatusSnapshot() {
        boolean gatewayEnabled = gatewayConfig != null && gatewayConfig.isEnabled();
        boolean connected = isStarted();
        String connectionId = session != null ? session.connectionId() : null;
        long heartbeat = session != null ? session.heartbeatIntervalSeconds() : -1L;
        return new StatusSnapshot(
                gatewayEnabled,
                connected,
                connectionId,
                heartbeat,
                lastConnectedAtMillis,
                reconnectDelayMs,
                pendingRequests.size(),
                peerVersion
        );
    }

    public <T> CompletableFuture<BeaconActionResponse<T>> sendAction(BeaconActionCall<T> call) {
        Objects.requireNonNull(call, "call");
        if (!isStarted()) {
            return failedFuture(new IllegalStateException("Beacon Provider gateway is not ready"));
        }
        String requestId = RequestIdGenerator.next();
        PendingRequest<T> pending = new PendingRequest<>(call);
        pendingRequests.put(requestId, pending);
        scheduleRequestTimeout(requestId, pending, call.getTimeout().toMillis());

        try {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("type", "request");
            GatewaySession s = session;
            if (s != null && s.connectionId() != null) {
                envelope.put("connectionId", s.connectionId());
            }
            ObjectNode body = mapper.createObjectNode();
            body.put("protocolVersion", PROTOCOL_VERSION);
            body.put("requestId", requestId);
            body.put("action", call.getAction());
            body.set("payload", mapper.valueToTree(call.getPayload() == null ? mapper.createObjectNode() : call.getPayload()));
            envelope.set("body", body);
            sendEnvelope(envelope);
        } catch (IOException e) {
            pendingRequests.remove(requestId);
            pending.completeExceptionally(e);
            handleConnectionLost(e);
        }

        return pending.getFuture();
    }

    private void submitConnect(long delayMs) {
        executor.schedule(this::connectOnce, Math.max(delayMs, 0L), TimeUnit.MILLISECONDS);
    }

    private void connectOnce() {
        if (stopRequested || gatewayConfig == null || !gatewayConfig.isEnabled()) {
            return;
        }
        closeSocket();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(gatewayConfig.getListenAddress(), gatewayConfig.getListenPort()), CONNECT_TIMEOUT_MILLIS);
            s.setTcpNoDelay(true);
            this.socket = s;
            this.input = new DataInputStream(s.getInputStream());
            this.output = new DataOutputStream(s.getOutputStream());
            this.handshakeFuture = new CompletableFuture<>();
            startReaderThread();
            sendHandshake();
            scheduleHandshakeTimeout();
        } catch (IOException e) {
            handleConnectionLost(e);
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(this::readLoop, "beacon-gateway-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            while (!stopRequested) {
                DataInputStream in = this.input;
                if (in == null) {
                    break;
                }
                int length;
                try {
                    length = in.readInt();
                } catch (IOException ex) {
                    throw ex;
                }
                if (length <= 0) {
                    continue;
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                JsonNode envelope = mapper.readTree(payload);
                handleEnvelope(envelope);
            }
        } catch (IOException e) {
            handleConnectionLost(e);
        }
    }

    private void handleEnvelope(JsonNode envelope) {
        String type = envelope.path("type").asText("");
        JsonNode body = envelope.path("body");
        switch (type) {
            case "handshake_ack":
                handleHandshakeAck(body);
                break;
            case "response":
                handleResponse(body);
                break;
            case "error":
                handleError(body);
                break;
            case "ping":
                handlePing(body);
                break;
            case "pong":
                break;
            default:
                if (plugin.getLogger().isLoggable(Level.FINE)) {
                    plugin.getLogger().fine("Unknown gateway frame type: " + type);
                }
        }
    }

    private void handleHandshakeAck(JsonNode body) {
        cancelHandshakeTimeout();
        if (handshakeFuture != null && handshakeFuture.isDone()) {
            return;
        }
        String connectionId = body.path("connectionId").asText(null);
        long heartbeatInterval = body.path("heartbeatIntervalSeconds").asLong(30L);
        String modVersion = body.path("modVersion").asText("unknown");
        this.peerVersion = modVersion;
        this.session = new GatewaySession(connectionId, heartbeatInterval);
        this.reconnectDelayMs = 1000L;
        this.lastConnectedAtMillis = System.currentTimeMillis();
        if (handshakeFuture != null) {
            handshakeFuture.complete(null);
        }
        plugin.getLogger().info("Beacon Gateway connected (connectionId=" + connectionId + ", modVersion=" + modVersion + ")");
        scheduleHeartbeat(heartbeatInterval);
    }

    private void handleResponse(JsonNode body) {
        String requestId = body.path("requestId").asText(null);
        if (requestId == null) {
            plugin.getLogger().warning("Gateway response missing requestId");
            return;
        }
        @SuppressWarnings("unchecked")
        PendingRequest<Object> pending = (PendingRequest<Object>) pendingRequests.remove(requestId);
        if (pending == null) {
            plugin.getLogger().warning("Gateway response for unknown requestId=" + requestId);
            return;
        }
        try {
            int protocolVersion = body.path("protocolVersion").asInt(PROTOCOL_VERSION);
            String resultRaw = body.path("result").asText("ERROR");
            BeaconResultCode result = parseResult(resultRaw);
            String message = body.path("message").asText("");
            JsonNode payloadNode = body.path("payload");
            Object payload = pending.deserializePayload(payloadNode);
            BeaconActionResponse<Object> response = new BeaconActionResponse<>(protocolVersion, requestId, result, message, payload);
            pending.complete(response);
        } catch (Exception ex) {
            pending.completeExceptionally(ex);
        }
    }

    private void handleError(JsonNode body) {
        String code = body.path("errorCode").asText("ERROR");
        String message = body.path("message").asText("");
        plugin.getLogger().warning("Beacon Gateway error: " + code + ", message=" + message);
        if (handshakeFuture != null && !handshakeFuture.isDone()) {
            handshakeFuture.completeExceptionally(new IllegalStateException(code + ": " + message));
        }
    }

    private void handlePing(JsonNode body) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "pong");
        if (session != null && session.connectionId() != null) {
            envelope.put("connectionId", session.connectionId());
        }
        envelope.set("body", body);
        try {
            sendEnvelope(envelope);
        } catch (IOException e) {
            handleConnectionLost(e);
        }
    }

    private void sendHandshake() throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("protocolVersion", PROTOCOL_VERSION);
        body.put("clientId", plugin.getName().toLowerCase());
        body.put("token", gatewayConfig.getAuthToken());
        ArrayNode capabilities = mapper.createArrayNode();
        capabilities.add("actions");
        body.set("capabilities", capabilities);

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", "handshake");
        envelope.set("body", body);
        sendEnvelope(envelope);
    }

    private void scheduleHandshakeTimeout() {
        cancelHandshakeTimeout();
        int timeoutSeconds = Math.max(1, gatewayConfig.getHandshakeTimeoutSeconds());
        handshakeTimeoutFuture = executor.schedule(() -> {
            CompletableFuture<Void> future = this.handshakeFuture;
            if (future != null && !future.isDone()) {
                future.completeExceptionally(new TimeoutException("Gateway handshake timed out"));
                handleConnectionLost(new IOException("Gateway handshake timeout"));
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    private void scheduleHeartbeat(long heartbeatIntervalSeconds) {
        cancelHeartbeat();
        if (heartbeatIntervalSeconds <= 0) {
            return;
        }
        heartbeatFuture = executor.scheduleAtFixedRate(() -> {
            try {
                ObjectNode body = mapper.createObjectNode();
                body.put("seq", pingSeq.getAndIncrement());
                ObjectNode envelope = mapper.createObjectNode();
                envelope.put("type", "ping");
                if (session != null && session.connectionId() != null) {
                    envelope.put("connectionId", session.connectionId());
                }
                envelope.set("body", body);
                sendEnvelope(envelope);
            } catch (IOException e) {
                handleConnectionLost(e);
            }
        }, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
    }

    private void cancelHandshakeTimeout() {
        if (handshakeTimeoutFuture != null) {
            handshakeTimeoutFuture.cancel(true);
            handshakeTimeoutFuture = null;
        }
    }

    private void sendEnvelope(ObjectNode envelope) throws IOException {
        byte[] data = mapper.writeValueAsBytes(envelope);
        DataOutputStream out = this.output;
        if (out == null) {
            throw new IOException("Gateway connection is not open");
        }
        synchronized (writeLock) {
            out.writeInt(data.length);
            out.write(data);
            out.flush();
        }
    }

    private void handleConnectionLost(Throwable cause) {
        if (stopRequested) {
            return;
        }
        closeSocket();
        failAllPending(cause != null ? cause : new IOException("Gateway disconnected"));
        if (handshakeFuture != null && !handshakeFuture.isDone()) {
            handshakeFuture.completeExceptionally(cause != null ? cause : new IOException("Gateway disconnected"));
        }
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS);
        plugin.getLogger().warning("Beacon Gateway connection lost: " + (cause != null ? cause.getMessage() : "unknown") + ". Reconnecting in " + delay + "ms");
        submitConnect(delay);
    }

    private void closeSocket() {
        cancelHeartbeat();
        cancelHandshakeTimeout();
        this.session = null;
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        closeQuietly(input);
        closeQuietly(output);
        closeQuietly(socket);
        this.input = null;
        this.output = null;
        this.socket = null;
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {}
    }

    private void failAllPending(Throwable cause) {
        if (cause == null) {
            cause = new IOException("Gateway connection closed");
        }
        for (Map.Entry<String, PendingRequest<?>> entry : pendingRequests.entrySet()) {
            PendingRequest<?> pending = entry.getValue();
            if (pending != null) {
                pending.completeExceptionally(cause);
            }
        }
        pendingRequests.clear();
    }

    private <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(throwable);
        return future;
    }

    private void scheduleRequestTimeout(String requestId, PendingRequest<?> pending, long timeoutMillis) {
        long delay = timeoutMillis > 0 ? timeoutMillis : 10_000L;
        ScheduledFuture<?> future = executor.schedule(() -> {
            PendingRequest<?> removed = pendingRequests.remove(requestId);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("Beacon Provider request timed out: " + requestId));
            }
        }, delay, TimeUnit.MILLISECONDS);
        pending.setTimeoutTask(future);
    }

    private BeaconResultCode parseResult(String raw) {
        try {
            return BeaconResultCode.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return BeaconResultCode.ERROR;
        }
    }

    private static final class GatewaySession {
        private final String connectionId;
        private final long heartbeatIntervalSeconds;

        private GatewaySession(String connectionId, long heartbeatIntervalSeconds) {
            this.connectionId = connectionId;
            this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        }

        public String connectionId() {
            return connectionId;
        }

        public long heartbeatIntervalSeconds() {
            return heartbeatIntervalSeconds;
        }
    }

    public static final class StatusSnapshot {
        private final boolean gatewayEnabled;
        private final boolean connected;
        private final String connectionId;
        private final long heartbeatSeconds;
        private final long lastConnectedAtMillis;
        private final long reconnectDelayMillis;
        private final int pendingRequests;
        private final String modVersion;

        private StatusSnapshot(boolean gatewayEnabled,
                               boolean connected,
                               String connectionId,
                               long heartbeatSeconds,
                               long lastConnectedAtMillis,
                               long reconnectDelayMillis,
                               int pendingRequests,
                               String modVersion) {
            this.gatewayEnabled = gatewayEnabled;
            this.connected = connected;
            this.connectionId = connectionId;
            this.heartbeatSeconds = heartbeatSeconds;
            this.lastConnectedAtMillis = lastConnectedAtMillis;
            this.reconnectDelayMillis = reconnectDelayMillis;
            this.pendingRequests = pendingRequests;
            this.modVersion = modVersion;
        }

        public boolean gatewayEnabled() {
            return gatewayEnabled;
        }

        public boolean isConnected() {
            return connected;
        }

        public String getConnectionId() {
            return connectionId;
        }

        public long getHeartbeatSeconds() {
            return heartbeatSeconds;
        }

        public long getLastConnectedAtMillis() {
            return lastConnectedAtMillis;
        }

        public long getReconnectDelayMillis() {
            return reconnectDelayMillis;
        }

        public int getPendingRequests() {
            return pendingRequests;
        }

        public String getModVersion() {
            return modVersion;
        }
    }

    private final class PendingRequest<T> {
        private final BeaconActionCall<T> call;
        private final CompletableFuture<BeaconActionResponse<T>> future = new CompletableFuture<>();
        private ScheduledFuture<?> timeoutTask;

        private PendingRequest(BeaconActionCall<T> call) {
            this.call = call;
        }

        public CompletableFuture<BeaconActionResponse<T>> getFuture() {
            return future;
        }

        public void setTimeoutTask(ScheduledFuture<?> timeoutTask) {
            this.timeoutTask = timeoutTask;
        }

        public void complete(BeaconActionResponse<T> response) {
            cancelTimeout();
            future.complete(response);
        }

        public void completeExceptionally(Throwable throwable) {
            cancelTimeout();
            future.completeExceptionally(throwable);
        }

        public Object deserializePayload(JsonNode payloadNode) {
            if (payloadNode == null || payloadNode.isMissingNode() || payloadNode.isNull()) {
                return null;
            }
            Class<?> raw = call.getResponseType().getRawClass();
            if (raw == Void.class || raw == Void.TYPE) {
                return null;
            }
            return mapper.convertValue(payloadNode, call.getResponseType());
        }

        private void cancelTimeout() {
            if (timeoutTask != null) {
                timeoutTask.cancel(true);
                timeoutTask = null;
            }
        }
    }
}
