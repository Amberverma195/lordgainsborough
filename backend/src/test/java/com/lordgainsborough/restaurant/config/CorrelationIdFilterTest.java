package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.SignupOtpObservability;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void preservesValidatedCorrelationIdWithoutReadingRequestBody() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(CorrelationIdFilter.HEADER)).thenReturn("request-abc_123");

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader(CorrelationIdFilter.HEADER, "request-abc_123");
        verify(chain).doFilter(request, response);
        assertNull(MDC.get(SignupOtpObservability.CORRELATION_MDC_KEY));
    }

    @Test
    void logsServerErrorStatusAndClearsMdc() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/orders");
        when(response.getStatus()).thenReturn(500);

        filter.doFilterInternal(request, response, chain);

        verify(response).getStatus();
        assertNull(MDC.get(SignupOtpObservability.CORRELATION_MDC_KEY));
    }

    @Test
    void handlesExceptionAndClearsMdc() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/orders");
        org.mockito.Mockito.doThrow(new IllegalStateException("Boom")).when(chain).doFilter(request, response);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> filter.doFilterInternal(request, response, chain));

        assertNull(MDC.get(SignupOtpObservability.CORRELATION_MDC_KEY));
    }

    @Test
    void rejectsValuesThatCouldBeRawIpOtpOrLogInjection() {
        String rawIp = CorrelationIdFilter.validOrGenerated("203.0.113.7");
        String rawOtp = CorrelationIdFilter.validOrGenerated("123456");
        String injected = CorrelationIdFilter.validOrGenerated("good\nforged=true");

        assertFalse(rawIp.equals("203.0.113.7"));
        assertFalse(rawOtp.equals("123456"));
        assertFalse(injected.contains("\n"));
        assertEquals(36, rawIp.length());
        assertEquals(36, rawOtp.length());
        assertEquals(36, injected.length());
    }
}
