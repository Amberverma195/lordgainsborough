package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerNoShowServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CustomerOrderRepository orderRepository = mock(CustomerOrderRepository.class);
    private final CustomerNoShowService service = new CustomerNoShowService(userRepository, orderRepository);

    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(userRepository, orderRepository);
    }

    @Test
    void pickupDisabledAtThreeNoShows() {
        User user = new User();
        user.setNoShowCount(2);
        assertFalse(service.isPickupDisabled(user));

        user.setNoShowCount(3);
        assertTrue(service.isPickupDisabled(user));
    }

    @Test
    void recordNoShowSyncsCountFromOrdersByEmail() {
        User user = new User();
        user.setEmail("guest@example.com");
        user.setNoShowCount(0);
        when(userRepository.findByEmailIgnoreCase("guest@example.com")).thenReturn(Optional.of(user));
        when(orderRepository.countByCustomerEmailIgnoreCaseAndStatus("guest@example.com", "NO_SHOW"))
                .thenReturn(3L);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerOrder order = new CustomerOrder();
        order.setCustomerEmail("guest@example.com");
        service.recordNoShow(order);

        assertEquals(3, user.getNoShowCount());
        verify(userRepository).save(user);
        assertTrue(service.isPickupDisabled(user));
    }

    @Test
    void recordNoShowUsesPhoneWhenEmailMissing() {
        User user = new User();
        user.setPhone("+1 (416)-555-1212");
        user.setPhoneNormalized("4165551212");
        user.setNoShowCount(0);
        when(userRepository.findByPhoneNormalized("4165551212")).thenReturn(List.of(user));
        when(orderRepository.countByCustomerPhoneAndStatus(eq("+1 (416)-555-1212"), eq("NO_SHOW")))
                .thenReturn(1L);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerOrder order = new CustomerOrder();
        order.setCustomerPhone("+1 (416)-555-1212");
        service.recordNoShow(order);

        assertEquals(1, user.getNoShowCount());
        verify(userRepository).save(user);
    }

    @Test
    void recordNoShowDoesNothingWithoutMatchingCustomer() {
        when(userRepository.findByEmailIgnoreCase("unknown@example.com")).thenReturn(Optional.empty());

        CustomerOrder order = new CustomerOrder();
        order.setCustomerEmail("unknown@example.com");
        service.recordNoShow(order);

        verify(userRepository, never()).save(any());
    }
}
