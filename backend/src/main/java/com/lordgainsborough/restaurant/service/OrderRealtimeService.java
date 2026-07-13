package com.lordgainsborough.restaurant.service;

import com.corundumstudio.socketio.SocketIOServer;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class OrderRealtimeService {
    private final SocketIOServer socketIoServer;
    private final CustomerOrderPresentationService presentationService;

    public OrderRealtimeService(
            SocketIOServer socketIoServer,
            CustomerOrderPresentationService presentationService) {
        this.socketIoServer = socketIoServer;
        this.presentationService = presentationService;
    }

    public void orderCreated(CustomerOrder order) {
        CustomerOrder payload = presentationService.attachCustomerOrderCount(order);
        socketIoServer.getRoomOperations("kds").sendEvent("order.created", payload);
        socketIoServer.getRoomOperations("order:" + order.getId()).sendEvent("order.updated", payload);
    }

    public void orderUpdated(CustomerOrder order) {
        CustomerOrder payload = presentationService.attachCustomerOrderCount(order);
        socketIoServer.getRoomOperations("kds").sendEvent("order.updated", payload);
        socketIoServer.getRoomOperations("order:" + order.getId()).sendEvent("order.updated", payload);
    }

    public void chatMessage(CustomerOrder order, CustomerOrder.OrderMessage message) {
        Map<String, Object> payload = Map.of(
                "orderId", order.getId(),
                "message", message
        );
        socketIoServer.getRoomOperations("kds").sendEvent("chat.message", payload);
        socketIoServer.getRoomOperations("order:" + order.getId()).sendEvent("chat.message", payload);
    }
}
