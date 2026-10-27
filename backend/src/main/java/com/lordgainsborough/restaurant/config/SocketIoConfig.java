package com.lordgainsborough.restaurant.config;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.protocol.Packet;
import com.corundumstudio.socketio.protocol.PacketType;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.service.SocketOrderAuthorizationService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;

@org.springframework.context.annotation.Configuration
public class SocketIoConfig {
    private static final String ROOM_GRANT = SocketIoConfig.class.getName() + ".roomGrant";
    private SocketOrderAuthorizationService authorizationService;

    public SocketOrderAuthorizationService getAuthorizationService() { return authorizationService; }
    @Value("${socketio.host:0.0.0.0}")
    private String host;

    @Value("${socketio.port:9092}")
    private int port;

    @Value("${socketio.origin:http://localhost:3000}")
    private String origin;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIoServer(
            SocketOrderAuthorizationService authorization,
            ClientIpResolver clientIpResolver) {
        // initMethod=start executes before SmartInitializingSingleton validators. Fail closed HERE first.
        authorization.validateBackendCoherence();
        this.authorizationService = authorization;
        Configuration config = new Configuration();
        config.setHostname(host);
        config.setPort(port);
        config.setOrigin(origin);
        // Netty-socketio's default ObjectMapper cannot serialize LocalDateTime, which
        // silently drops order.created / order.updated events to KDS and customers.
        config.setJsonSupport(new JavaTimeJacksonJsonSupport());
        config.setAuthorizationListener(handshake -> {
            try {
                // Role merely declares kitchen intent. Only the actual IP/settings gate authorizes it.
                return !SocketOrderAuthorizationService.isKitchenRole(handshake.getSingleUrlParam("role"))
                        || authorization.kitchenAllowed(resolveHandshakeIp(handshake, clientIpResolver))
                        ? AuthorizationResult.SUCCESSFUL_AUTHORIZATION : AuthorizationResult.FAILED_AUTHORIZATION;
            } catch (RuntimeException failure) {
                return AuthorizationResult.FAILED_AUTHORIZATION;
            }
        });

        SocketIOServer server = new SocketIOServer(config);
        server.getNamespace(com.corundumstudio.socketio.namespace.Namespace.DEFAULT_NAME).addAuthTokenListener((authToken, client) -> {
            synchronized (client) {
                try {
                    revoke(client);
                    var handshake = client.getHandshakeData();
                    var grant = authorization.authorize(handshake.getSingleUrlParam("orderId"),
                            handshake.getSingleUrlParam("role"), authValue(authToken, "token", 8192),
                            authValue(authToken, "trackingToken", 43), resolveHandshakeIp(handshake, clientIpResolver));
                    if (grant.isEmpty()) return deniedAuthorization();
                    // Engine.IO can invoke connect listeners before namespace authentication.
                    // Admit rooms here after the real auth payload has been validated.
                    admit(client, grant.get());
                    return AuthTokenResult.AuthTokenResultSuccess;
                } catch (RuntimeException failure) {
                    safelyRevoke(client);
                    return deniedAuthorization();
                }
            }
        });

        server.addConnectListener(client -> {
            synchronized (client) {
                try {
                    SocketOrderAuthorizationService.RoomGrant grant = client.get(ROOM_GRANT);
                    clearProtectedRooms(client);
                    var handshake = client.getHandshakeData();
                    String orderId = handshake.getSingleUrlParam("orderId");
                    String role = handshake.getSingleUrlParam("role");
                    String ip = resolveHandshakeIp(handshake, clientIpResolver);
                    // netty-socketio 2.0.14 skips namespace auth for a null auth payload (and older Engine.IO).
                    // Such a path can enter no order room; kitchen-only connections still pass the real IP gate.
                    if (grant == null) {
                        if (orderId != null) {
                            // This is provisional transport connectivity, not an order-room grant.
                            // The library skips namespace authentication for an absent auth payload.
                            java.util.concurrent.CompletableFuture.delayedExecutor(3, java.util.concurrent.TimeUnit.SECONDS)
                                    .execute(() -> {
                                        synchronized (client) {
                                            if (client.isChannelOpen() && client.get(ROOM_GRANT) == null) denyConnection(client);
                                        }
                                    });
                            return;
                        }
                        grant = authorization.authorize(null, role, null, null, ip).orElse(null);
                    }
                    if (grant == null || !Objects.equals(grant.orderId(), orderId)
                            || grant.kitchen() != SocketOrderAuthorizationService.isKitchenRole(role)
                            || (grant.kitchen() && !authorization.kitchenAllowed(ip))) {
                        denyConnection(client);
                        return;
                    }
                    admit(client, grant);
                } catch (RuntimeException failure) {
                    denyConnection(client);
                }
            }
        });
        server.addDisconnectListener(client -> {
            synchronized (client) {
                // The library removes namespace rooms before invoking disconnect listeners.
                try { revoke(client); } catch (RuntimeException ignored) { /* No stale grant retained intentionally. */ }
            }
        });

        return server;
    }

    private static void admit(SocketIOClient client, SocketOrderAuthorizationService.RoomGrant grant) {
        clearProtectedRooms(client);
        if (grant.orderId() != null) client.joinRoom("order:" + grant.orderId());
        if (grant.kitchen()) client.joinRoom("kds");
        client.set(ROOM_GRANT, grant);
    }

    private static AuthTokenResult deniedAuthorization() {
        return new AuthTokenResult(false, Map.of("message", "Order access denied"));
    }

    private static void clearProtectedRooms(SocketIOClient client) {
        var rooms = client.getAllRooms();
        if (rooms == null) throw new IllegalStateException("Socket room membership is unavailable");
        for (String room : new HashSet<>(rooms))
            if ("kds".equals(room) || room.startsWith("order:")) client.leaveRoom(room);
    }

    private static void revoke(SocketIOClient client) {
        try { client.del(ROOM_GRANT); }
        finally { clearProtectedRooms(client); }
    }

    private static void safelyRevoke(SocketIOClient client) {
        try { revoke(client); }
        catch (RuntimeException failure) {
            try { client.disconnect(); } catch (RuntimeException ignored) { /* Remain denied. */ }
        }
    }

    private static void denyConnection(SocketIOClient client) {
        safelyRevoke(client);
        try {
            Packet denial = new Packet(PacketType.MESSAGE, client.getEngineIOVersion());
            denial.setSubType(PacketType.ERROR);
            denial.setNsp(client.getNamespace().getName());
            denial.setData(Map.of("message", "Order access denied"));
            client.send(denial);
        } catch (RuntimeException ignored) { /* A failed denial delivery must never grant access. */ }
        finally {
            try { client.disconnect(); } catch (RuntimeException ignored) { /* No protected rooms remain. */ }
        }
    }

    private static String resolveHandshakeIp(HandshakeData handshakeData, ClientIpResolver clientIpResolver) {
        String remoteAddr = null;
        SocketAddress address = handshakeData.getAddress();
        if (address instanceof InetSocketAddress inet) {
            if (inet.getAddress() != null) {
                remoteAddr = inet.getAddress().getHostAddress();
            }
        }
        String xff = handshakeData.getHttpHeaders() != null
                ? handshakeData.getHttpHeaders().get("X-Forwarded-For")
                : null;
        String xRealIp = handshakeData.getHttpHeaders() != null
                ? handshakeData.getHttpHeaders().get("X-Real-IP")
                : null;
        return clientIpResolver.resolve(remoteAddr, xff, xRealIp);
    }

    /**
     * Reads a value from the Socket.IO client {@code auth} payload so secrets (JWT,
     * order tracking token) travel in the handshake body rather than the URL query string.
     */
    private String authValue(Object authToken, String key, int maximumLength) {
        Object value = null;
        if (authToken instanceof Map<?, ?> authMap) value = authMap.get(key);
        else if (authToken instanceof JsonNode authNode) {
            JsonNode field = authNode.get(key);
            if (field != null && field.isTextual()) value = field.textValue();
        }
        return value instanceof String text && text.length() <= maximumLength && !text.isBlank() ? text : null;
    }

    /**
     * Registers JavaTimeModule so order timestamps serialize as ISO-8601 strings.
     */
    private static final class JavaTimeJacksonJsonSupport extends JacksonJsonSupport {
        private JavaTimeJacksonJsonSupport() {
            super(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }
}
