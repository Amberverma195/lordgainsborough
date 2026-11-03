package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.Reservation;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.service.ReservationRateLimiter;
import com.lordgainsborough.restaurant.service.ReservationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.OptionalLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationControllerTest {

    private ReservationStore store;
    private ReservationRateLimiter rateLimiter;
    private ClientIpResolver clientIpResolver;
    private ReservationController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(ReservationStore.class);
        rateLimiter = mock(ReservationRateLimiter.class);
        clientIpResolver = mock(ClientIpResolver.class);
        when(clientIpResolver.resolve(any(), any(), any())).thenReturn("192.168.1.50");

        controller = new ReservationController(store, rateLimiter, clientIpResolver);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Allows reservation creation when rate limiter permits")
    void testCreateReservationAllowed() throws Exception {
        when(rateLimiter.consumeIpRequest("192.168.1.50")).thenReturn(OptionalLong.empty());
        when(store.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String json = """
                {
                    "name": "Amber",
                    "email": "amber@example.com",
                    "phone": "519-555-1234",
                    "date": "2026-11-20",
                    "time": "18:00",
                    "partySize": 2
                }
                """;

        mvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        verify(store, times(1)).create(any());
    }

    @Test
    @DisplayName("Rejects reservation with 429 Too Many Requests when rate limiter denies")
    void testCreateReservationRateLimited() throws Exception {
        when(rateLimiter.consumeIpRequest("192.168.1.50")).thenReturn(OptionalLong.of(60L));

        String json = """
                {
                    "name": "Amber",
                    "email": "amber@example.com",
                    "phone": "519-555-1234",
                    "date": "2026-11-20",
                    "time": "18:00",
                    "partySize": 2
                }
                """;

        mvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        verify(store, never()).create(any());
    }
}
