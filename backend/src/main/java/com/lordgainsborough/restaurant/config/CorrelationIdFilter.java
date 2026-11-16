package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.SignupOtpObservability;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Adds a validated correlation id to logs and responses without reading or logging request bodies,
 * and records structured error observability for 5xx server responses and unhandled exceptions.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
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
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } catch (Throwable ex) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("HTTP request aborted with unhandled exception: method={} uri={} duration_ms={} error={}",
                    request.getMethod(), request.getRequestURI(), durationMs, ex.getMessage(), ex);
            throw ex;
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            int status = response.getStatus();
            if (status >= 500) {
                log.error("HTTP server error response: method={} uri={} status={} duration_ms={}",
                        request.getMethod(), request.getRequestURI(), status, durationMs);
            }
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

