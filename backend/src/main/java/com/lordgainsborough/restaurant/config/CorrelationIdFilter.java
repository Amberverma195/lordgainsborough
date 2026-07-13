package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.SignupOtpObservability;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Adds a validated correlation id to logs and responses without reading or logging request bodies.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String previous = MDC.get(SignupOtpObservability.CORRELATION_MDC_KEY);
        String correlationId = validOrGenerated(request.getHeader(HEADER));
        MDC.put(SignupOtpObservability.CORRELATION_MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(SignupOtpObservability.CORRELATION_MDC_KEY);
            } else {
                MDC.put(SignupOtpObservability.CORRELATION_MDC_KEY, previous);
            }
        }
    }

    static String validOrGenerated(String supplied) {
        if (supplied != null
                && SAFE_CORRELATION_ID.matcher(supplied.trim()).matches()
                && !supplied.trim().matches("\\d{6}")) {
            return supplied.trim();
        }
        return UUID.randomUUID().toString();
    }
}
