package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.dto.AdminCustomerSearchResult;
import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminCustomerSearchServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomerOrderRepository orderRepository = mock(CustomerOrderRepository.class);
    private final AdminCustomerSearchService service = new AdminCustomerSearchService(userRepository, orderRepository);

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(userRepository, orderRepository);
    }

    @Test
    void returnsEmptyForShortQuery() {
        assertTrue(service.search("a").isEmpty());
    }

    @Test
    void searchesByNameAndAttachesOrders() {
        User user = new User();
        user.setId("user-1");
        user.setName("Amber Verma");
        user.setEmail("amber@example.com");
        user.setPhone("+1 (519)-614-3615");
        user.setPhoneNormalized("5196143615");
        user.setNoShowCount(1);

        CustomerOrder order = new CustomerOrder();
        order.setId("order-1");
        order.setCustomerEmail("amber@example.com");
        order.setCreatedAt(LocalDateTime.now());

        when(userRepository.findByNameContainingIgnoreCase("amber")).thenReturn(List.of(user));
        when(userRepository.findByEmailContainingIgnoreCase("amber")).thenReturn(List.of());
        when(orderRepository.findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc("amber@example.com"))
                .thenReturn(List.of(order));
        when(orderRepository.findByCustomerPhoneOrderByCreatedAtDesc("+1 (519)-614-3615"))
                .thenReturn(List.of());

        List<AdminCustomerSearchResult> results = service.search("amber");

        assertEquals(1, results.size());
        assertEquals("Amber Verma", results.get(0).getName());
        assertEquals(1, results.get(0).getOrders().size());
        assertEquals("order-1", results.get(0).getOrders().get(0).getId());
    }
}
