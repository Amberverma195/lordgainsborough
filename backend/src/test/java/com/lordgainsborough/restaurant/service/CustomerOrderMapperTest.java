package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerOrderMapperTest {
    @Test void everyLifecycleFieldAndChildBackReferenceRoundTrips() {
        var order=new CustomerOrder(); order.setId("order"); order.setCustomerName("Name");
        order.setCustomerEmail("email@example.com"); order.setCustomerPhone("phone"); order.setPickupDate("date");
        order.setPickupTime("time"); order.setSpecialInstructions("instructions"); order.setStatus("READY");
        order.setAverageReadyMinutes(40); order.setSubtotal(1); order.setTax(0.13); order.setTotal(1.13);
        var now=LocalDateTime.of(2026,10,26,18,0); order.setCreatedAt(now); order.setAcceptedAt(now.plusMinutes(1));
        order.setReadyAt(now.plusMinutes(2)); order.setPickedUpAt(now.plusMinutes(3)); order.setNoShowAt(now.plusMinutes(4));
        order.setReviewed(true); order.setTrackingTokenHash("private hash");
        order.setIdempotencyKey("idemp-key-12345");
        var item=new CustomerOrder.OrderItem(); item.setMenuItemId("menu"); item.setName("Dish"); item.setQuantity(1);
        item.setUnitPrice(1); item.setTotal(1); item.setNotes("notes"); order.getItems().add(item);
        var message=new CustomerOrder.OrderMessage(); message.setId("message"); message.setSenderRole("CUSTOMER");
        message.setSenderName("Customer"); message.setText("text"); message.setCreatedAt(now); order.getMessages().add(message);
        var mapper=new CustomerOrderMapper(); var entity=mapper.toJpa(order);
        assertThat(entity.getItems().getFirst().getOrder()).isSameAs(entity);
        assertThat(entity.getMessages().getFirst().getOrder()).isSameAs(entity);
        assertThat(entity.getTotal()).isEqualTo(new BigDecimal("1.13"));
        assertThat(mapper.fromJpa(entity)).usingRecursiveComparison().isEqualTo(order);
    }
    @ParameterizedTest @ValueSource(strings={"-0.01","100000000.00"})
    void invalidAuthoritativeCatalogPriceFailsBeforeDoubleConversion(String price) {
        var menu=mock(JpaMenuItemRepository.class);
        when(menu.findById("menu")).thenReturn(Optional.of(new JpaMenuItem("menu","Dish","",new BigDecimal(price),"Category",null)));
        var store=new PostgresCustomerOrderStore(mock(JpaCustomerOrderRepository.class),menu,mock(JpaOrderReviewRepository.class),
                mock(JpaStoreSettingsRepository.class),mock(JpaUserRepository.class),mock(JpaTransactionManager.class));
        assertThatThrownBy(() -> store.resolveMenuItem("menu","Dish")).isInstanceOf(IllegalArgumentException.class);
    }
}
