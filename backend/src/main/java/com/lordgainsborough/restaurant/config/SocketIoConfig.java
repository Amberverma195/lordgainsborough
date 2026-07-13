package com.lordgainsborough.restaurant.config;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOServer;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.security.JwtUtil;
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
            JwtUtil jwtUtil,
            CustomerOrderRepository orderRepository,
            OrderAccessService orderAccessService,
            KdsAccessService kdsAccessService,
            ClientIpResolver clientIpResolver) {
        Configuration config = new Configuration();
        config.setHostname(host);
        config.setPort(port);
        config.setOrigin("*");
        config.setAuthorizationListener(handshakeData -> {
            String role = handshakeData.getSingleUrlParam("role");
            String token = authValue(handshakeData, "token");

            // Layer 3: KDS realtime room is IP-gated (no login).
            if (isKdsSocketRole(role)) {
                String clientIp = resolveHandshakeIp(handshakeData, clientIpResolver);
                return kdsAccessService.isIpAllowed(clientIp)
                        ? AuthorizationResult.SUCCESSFUL_AUTHORIZATION
                        : AuthorizationResult.FAILED_AUTHORIZATION;
            }

            // Customer connections may only join an order room they can prove access to.
            String orderId = handshakeData.getSingleUrlParam("orderId");
            if (orderId != null && !orderId.isBlank()) {
                String trackingToken = authValue(handshakeData, "trackingToken");
                Optional<CustomerOrder> orderOpt = orderRepository.findById(orderId);
                boolean authorized = orderOpt
                        .filter(order -> orderAccessService.canAccessWithJwtToken(order, token, trackingToken))
                        .isPresent();
                return authorized
                        ? AuthorizationResult.SUCCESSFUL_AUTHORIZATION
                        : AuthorizationResult.FAILED_AUTHORIZATION;
            }

            return AuthorizationResult.SUCCESSFUL_AUTHORIZATION;
        });

        SocketIOServer server = new SocketIOServer(config);
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
    private String authValue(HandshakeData handshakeData, String key) {
        Object authToken = handshakeData.getAuthToken();
        if (authToken instanceof Map<?, ?> authMap) {
            Object value = authMap.get(key);
            if (value != null) {
                String text = String.valueOf(value);
                return text.isBlank() ? null : text;
            }
        }
        return null;
    }
}
