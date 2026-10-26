package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KdsControllerTest {
    private final CustomerOrderRepository orders=mock(CustomerOrderRepository.class);
    private final OrderRealtimeService realtime=mock(OrderRealtimeService.class);
    private final CustomerOrderPresentationService presentation=mock(CustomerOrderPresentationService.class);
    private final CustomerNoShowService noShows=mock(CustomerNoShowService.class);
    private final KdsController controller=new KdsController(orders,realtime,presentation,noShows);
    private CustomerOrder order;
    @BeforeEach void fixture() {
        order=new CustomerOrder(); order.setId("order");
        when(orders.findById("order")).thenReturn(Optional.of(order));
        when(orders.save(any())).thenAnswer(call -> call.getArgument(0));
        when(presentation.attachCustomerOrderCount(any())).thenAnswer(call -> call.getArgument(0));
    }
    @Test void mongoFirstNoShowSemanticsRemainUnchangedOnRepeatedAndReenteredStatus() {
        controller.updateStatus("order",Map.of("status"," no_show ")); var first=order.getNoShowAt();
        controller.updateStatus("order",Map.of("status","NO_SHOW")); controller.updateStatus("order",Map.of("status","READY"));
        controller.updateStatus("order",Map.of("status","NO_SHOW"));
        assertThat(first).isNotNull(); assertThat(order.getNoShowAt()).isEqualTo(first);
        verify(noShows,times(1)).recordNoShow(order); verify(realtime,times(4)).orderUpdated(order);
    }
    @Test void invalidAndMissingStatusKeep400And404() {
        assertThat(controller.updateStatus("order",Map.of("status","OTHER")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.updateStatus("missing",Map.of("status","READY")).getStatusCode().value()).isEqualTo(404);
        verify(orders,never()).save(any());
    }
    @Test void mongoMessagesAppendAndReturnOriginalKitchenContract() {
        var earlier=new CustomerOrder.OrderMessage(); earlier.setId("customer"); order.getMessages().add(earlier);
        var response=controller.addKdsMessage("order",Map.of("text"," hello "));
        assertThat(response.getStatusCode().value()).isEqualTo(200); var message=response.getBody();
        assertThat(message.getSenderRole()).isEqualTo("KDS"); assertThat(message.getSenderName()).isEqualTo("Kitchen");
        assertThat(message.getText()).isEqualTo("hello"); assertThat(message.getId()).isNotBlank(); assertThat(message.getCreatedAt()).isNotNull();
        assertThat(order.getMessages()).containsExactly(earlier,message);
        verify(realtime).chatMessage(order,message); verify(realtime).orderUpdated(order);
    }
    @Test void missingAndBlankMessagesKeepValidationOrder() {
        assertThat(controller.addKdsMessage("missing",Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.addKdsMessage("missing",Map.of("text"," ")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.addKdsMessage("missing",Map.of("text","hello")).getStatusCode().value()).isEqualTo(404);
        verify(orders,never()).save(any());
    }
    @Test void readyTimeOverflowIsClampedBeforeIntConversion() {
        assertThat(controller.updateReadyTime("order",Map.of("averageReadyMinutes",new BigInteger("999999999999999999999999"))).getBody().getAverageReadyMinutes()).isEqualTo(120);
        assertThat(controller.updateReadyTime("order",Map.of("averageReadyMinutes",Long.MIN_VALUE)).getBody().getAverageReadyMinutes()).isEqualTo(5);
        assertThat(controller.updateReadyTime("order",Map.of()).getBody().getAverageReadyMinutes()).isEqualTo(25);
    }
    @Test void socketDeliveryFailureCannotChangeSuccessfulMongoMutation() {
        doThrow(new IllegalStateException("delivery unavailable")).when(realtime).orderUpdated(any());
        assertThat(controller.updateStatus("order",Map.of("status","READY")).getStatusCode().value()).isEqualTo(200);
        assertThat(order.getStatus()).isEqualTo("READY"); verify(orders).save(order);
    }
}
