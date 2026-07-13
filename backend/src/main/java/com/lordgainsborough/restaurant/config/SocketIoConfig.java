package com.lordgainsborough.restaurant.config;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.security.RoleAccess;
import com.lordgainsborough.restaurant.service.KdsAccessService;
import com.lordgainsborough.restaurant.service.OrderAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Optional;

@org.springframework.context.annotation.Configuration
public class SocketIoConfig {
    @Value("${socketio.host:0.0.0.0}")
    private String host;

    @Value("${socketio.port:9092}")
    private int port;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIoServer(
            CustomerOrderRepository orderRepository,
            OrderAccessService orderAccessService,
            KdsAccessService kdsAccessService,
            ClientIpResolver clientIpResolver) {
        Configuration config = new Configuration();
        config.setHostname(host);
        config.setPort(port);
        config.setOrigin("*");
        // Netty-socketio's default ObjectMapper cannot serialize LocalDateTime, which
        // silently drops order.created / order.updated events to KDS and customers.
        config.setJsonSupport(new JavaTimeJacksonJsonSupport());
        config.setAuthorizationListener(handshakeData -> {
            String role = handshakeData.getSingleUrlParam("role");

            // Layer 3: KDS realtime room is IP-gated (no login).
            if (isKdsSocketRole(role)) {
                String clientIp = resolveHandshakeIp(handshakeData, clientIpResolver);
                return kdsAccessService.isIpAllowed(clientIp)
                        ? AuthorizationResult.SUCCESSFUL_AUTHORIZATION
                        : AuthorizationResult.FAILED_AUTHORIZATION;
            }

            // Socket.IO's auth payload is not available during this HTTP handshake.
            // Customer order authorization is performed by the namespace auth listener.
            return AuthorizationResult.SUCCESSFUL_AUTHORIZATION;
        });

        SocketIOServer server = new SocketIOServer(config);
        server.addNamespace("/").addAuthTokenListener((authToken, client) -> {
            String orderId = client.getHandshakeData().getSingleUrlParam("orderId");
            if (orderId == null || orderId.isBlank()) {
                return AuthTokenResult.AuthTokenResultSuccess;
            }

            String token = authValue(authToken, "token");
            String trackingToken = authValue(authToken, "trackingToken");
            Optional<CustomerOrder> orderOpt = orderRepository.findById(orderId);
            boolean authorized = orderOpt
                    .filter(order -> orderAccessService.canAccessWithJwtToken(order, token, trackingToken))
                    .isPresent();
            return authorized
                    ? AuthTokenResult.AuthTokenResultSuccess
                    : new AuthTokenResult(false, Map.of("message", "Order access denied"));
        });

        server.addConnectListener(client -> {
            String role = client.getHandshakeData().getSingleUrlParam("role");
            String orderId = client.getHandshakeData().getSingleUrlParam("orderId");
            if (isKdsSocketRole(role)) {
                client.joinRoom("kds");
            }
            if (orderId != null && !orderId.isBlank()) {
                client.joinRoom("order:" + orderId);
            }
        });

        return server;
    }

    private static boolean isKdsSocketRole(String role) {
        return "KDS".equalsIgnoreCase(role) || RoleAccess.canAccessKds(role);
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
    private String authValue(Object authToken, String key) {
        if (authToken instanceof Map<?, ?> authMap) {
            Object value = authMap.get(key);
            if (value != null) {
                String text = String.valueOf(value);
                return text.isBlank() ? null : text;
            }
        }
        if (authToken instanceof JsonNode authNode) {
            JsonNode value = authNode.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                return text.isBlank() ? null : text;
            }
        }
        return null;
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
