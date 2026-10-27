package com.lordgainsborough.restaurant.service;

import com.corundumstudio.socketio.SocketIOServer;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

@Service
public class OrderRealtimeService {
    private static final Logger log = LoggerFactory.getLogger(OrderRealtimeService.class);

    private final SocketIOServer socketIoServer;
    private final CustomerOrderPresentationService presentationService;

    public OrderRealtimeService(
            SocketIOServer socketIoServer,
            CustomerOrderPresentationService presentationService) {
        this.socketIoServer = socketIoServer;
        this.presentationService = presentationService;
    }

    @Autowired
    public OrderRealtimeService(SocketIOServer socketIoServer, CustomerOrderPresentationService presentationService,
            SocketOrderAuthorizationService authorization) {
        this(socketIoServer, presentationService);
        if (authorization.getPresentationService() != presentationService)
            throw new IllegalStateException("Socket.IO authorization and realtime counts must use the same selected presentation service");
    }
    public CustomerOrderPresentationService getPresentationService() { return presentationService; }
    public SocketIOServer getSocketIoServer() { return socketIoServer; }

    public void orderCreated(CustomerOrder order) {
        CustomerOrder payload = presentationService.attachCustomerOrderCount(order);
        orderCreatedWithCustomerCounts(payload);
    }

    /** PG-017 isolated adapter supplies counts from the committed PostgreSQL dataset. */
    public void orderCreatedWithCustomerCounts(CustomerOrder payload) {
        sendEvent("kds", "order.created", payload);
        sendEvent("order:" + payload.getId(), "order.updated", payload);
    }

    public void orderUpdated(CustomerOrder order) {
        CustomerOrder payload = presentationService.attachCustomerOrderCount(order);
        orderUpdatedWithCustomerCounts(payload);
    }

    public void orderUpdatedWithCustomerCounts(CustomerOrder payload) {
        sendEvent("kds", "order.updated", payload);
        sendEvent("order:" + payload.getId(), "order.updated", payload);
    }

    public void chatMessage(CustomerOrder order, CustomerOrder.OrderMessage message) {
        Map<String, Object> payload = Map.of(
                "orderId", order.getId(),
                "message", message
        );
        sendEvent("kds", "chat.message", payload);
        sendEvent("order:" + order.getId(), "chat.message", payload);
    }

    private void sendEvent(String room, String event, Object payload) {
        try {
            socketIoServer.getRoomOperations(room).sendEvent(event, payload);
        } catch (RuntimeException exception) {
            // Realtime delivery must never turn an already-persisted order into an
            // HTTP failure that encourages the customer to submit a duplicate.
            // Exception messages can contain serialization payloads; log only the intended event and room.
            log.warn("Unable to emit Socket.IO event {} to room {}", event, room);
        }
    }
}
