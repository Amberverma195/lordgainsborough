package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.security.AdminAuthInterceptor;
import com.lordgainsborough.restaurant.security.RoleAccess;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Optional;

/** Selected checkout/identity/KDS dependencies only; no Mongo fallback or room subscription API. */
@Service
public class SocketOrderAuthorizationService {
    private static final Logger log = LoggerFactory.getLogger(SocketOrderAuthorizationService.class);
    private final CustomerOrderStore orders;
    private final OrderAccessService access;
    private final UserAuthStore identities;
    private final KdsAccessService kitchenAccess;
    private final KdsOrderStore kitchenOrders;
    private final CustomerOrderPresentationService presentation;
    private final AdminAuthInterceptor administrator;

    /** Only non-secret room permissions cross from namespace authentication to connection admission. */
    public record RoomGrant(String orderId, boolean kitchen) {}

    public SocketOrderAuthorizationService(CustomerOrderStore orders, OrderAccessService access,
            UserAuthStore identities, KdsAccessService kitchenAccess, KdsOrderStore kitchenOrders,
            CustomerOrderPresentationService presentation, AdminAuthInterceptor administrator) {
        this.orders = orders; this.access = access; this.identities = identities;
        this.kitchenAccess = kitchenAccess; this.kitchenOrders = kitchenOrders;
        this.presentation = presentation; this.administrator = administrator;
        validateBackendCoherence();
    }
    public CustomerOrderStore getOrderStore() { return orders; }
    public UserAuthStore getIdentityStore() { return identities; }
    public OrderAccessService getOrderAccessService() { return access; }
    public CustomerOrderPresentationService getPresentationService() { return presentation; }

    /** Pure object/resource inspection: called before SocketIOServer construction/start, never queries data. */
    public void validateBackendCoherence() {
        if (access.getIdentityStore() != identities || administrator.getIdentityStore() != identities)
            throw incoherent("actual order/administrator identity selection");
        if (orders instanceof PostgresCustomerOrderStore checkout) {
            EntityManagerFactory emf = checkout.getEntityManagerFactory();
            if (emf == null || !(identities instanceof PostgresUserAuthStore users)
                    || users.getEntityManagerFactory() != emf
                    || users.getUserRepository() != checkout.getUserRepository()
                    || users.getOrderRepository() != checkout.getOrderRepository()
                    || !(kitchenAccess.getSettingsSource() instanceof PostgresKdsSettingsSource settings)
                    || settings.getEntityManagerFactory() != emf
                    || settings.getSettingsRepository() != checkout.getSettingsRepository()
                    || !(presentation.getCountStore() instanceof PostgresCustomerOrderCountStore counts)
                    || counts.getEntityManagerFactory() != emf || counts.getOrderRepository() != checkout.getOrderRepository()
                    || !(kitchenOrders instanceof PostgresKdsOrderStore kitchen)
                    || kitchen.getEntityManagerFactory() != emf || kitchen.getOrderRepository() != checkout.getOrderRepository()
                    || kitchen.getPresentationService() != presentation
                    || kitchen.getNoShowStore().getEntityManagerFactory() != emf
                    || kitchen.getNoShowStore().getUserRepository() != checkout.getUserRepository()
                    || kitchen.getNoShowStore().getOrderRepository() != checkout.getOrderRepository())
                throw incoherent("PostgreSQL orders, users, KDS settings and count persistence unit/repositories");
        } else if (!(orders instanceof MongoCustomerOrderStore) || !(identities instanceof MongoUserAuthStore)
                || !(kitchenOrders instanceof MongoKdsOrderStore)
                || !(kitchenAccess.getSettingsSource() instanceof MongoKdsSettingsSource)
                || !(presentation.getCountStore() instanceof MongoCustomerOrderCountStore)) {
            throw incoherent("default MongoDB dependencies");
        }
    }

    public Optional<RoomGrant> authorize(String orderId, String role, String jwt, String trackingToken, String clientIp) {
        try {
            boolean kitchen = isKitchenRole(role);
            if (kitchen && !kitchenAllowed(clientIp)) return Optional.empty();
            // Absence is harmless. A supplied blank/malformed ID never becomes anonymous room access.
            if (orderId == null) return Optional.of(new RoomGrant(null, kitchen));
            if (!validOrderId(orderId)) return Optional.empty();
            return orders.findById(orderId)
                    .filter(order -> orderId.equals(order.getId()))
                    .filter(order -> orders instanceof PostgresCustomerOrderStore
                            ? access.canAccessPostgresOrder(order, jwt == null ? null : "Bearer " + jwt, trackingToken)
                            : access.canAccessWithJwtToken(order, jwt, trackingToken))
                    .map(order -> new RoomGrant(orderId, kitchen));
        } catch (RuntimeException failure) {
            // Never reveal database errors, credentials, claims, order existence or exception payloads.
            log.warn("Socket order authorization failed");
            return Optional.empty();
        }
    }
    public boolean kitchenAllowed(String clientIp) {
        try { return kitchenAccess.isIpAllowed(clientIp); }
        catch (RuntimeException failure) {
            log.warn("Socket kitchen authorization failed");
            return false;
        }
    }
    public static boolean isKitchenRole(String role) {
        return "KDS".equalsIgnoreCase(role) || RoleAccess.canAccessKds(role);
    }
    private static boolean validOrderId(String id) {
        // Supports legacy ObjectIds/string IDs and PostgreSQL UUIDs within the existing VARCHAR(64).
        return id.length() <= 64 && id.matches("[A-Za-z0-9_-]+");
    }
    private static IllegalStateException incoherent(String detail) {
        return new IllegalStateException("Unsupported mixed-backend Socket.IO activation: " + detail);
    }
}
