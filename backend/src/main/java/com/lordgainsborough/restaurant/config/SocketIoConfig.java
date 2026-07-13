package com.lordgainsborough.restaurant.config;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOServer;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.security.RoleAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

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
            UserRepository userRepository,
            CustomerOrderRepository orderRepository,
            com.lordgainsborough.restaurant.service.OrderAccessService orderAccessService) {
        Configuration config = new Configuration();
        config.setHostname(host);
        config.setPort(port);
        config.setOrigin("*");
        config.setAuthorizationListener(handshakeData -> {
            String role = handshakeData.getSingleUrlParam("role");
            String token = authValue(handshakeData, "token");

            // Staff/admin connections must prove a valid privileged JWT.
            if (RoleAccess.canAccessKds(role)) {
                if (token == null || !jwtUtil.validateToken(token)) {
                    return AuthorizationResult.FAILED_AUTHORIZATION;
                }
                String email = jwtUtil.getEmailFromToken(token);
                boolean authorized = userRepository.findByEmailIgnoreCase(email)
                        .filter(user -> RoleAccess.canAccessKds(user.getRole()))
                        .isPresent();
                return authorized
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
            if (RoleAccess.canAccessKds(role)) {
                client.joinRoom("kds");
            }
            if (orderId != null && !orderId.isBlank()) {
                client.joinRoom("order:" + orderId);
            }
        });

        return server;
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
