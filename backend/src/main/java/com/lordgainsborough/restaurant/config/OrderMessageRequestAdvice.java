package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.controller.KdsController;
import com.lordgainsborough.restaurant.controller.OrderController;
import com.lordgainsborough.restaurant.security.ClientIpResolver;
import com.lordgainsborough.restaurant.service.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.OptionalLong;

/** Applies to resolved message handlers, including encoded URLs and unknown-length bodies. */
@ControllerAdvice
public class OrderMessageRequestAdvice extends RequestBodyAdviceAdapter {
    private final OrderMessageRateLimiter limiter;
    private final ClientIpResolver ips;
    private final CustomerOrderStore orders;
    private final OrderAccessService access;
    private final KdsAccessService kitchen;

    public OrderMessageRequestAdvice(OrderMessageRateLimiter limiter, ClientIpResolver ips,
            CustomerOrderStore orders, OrderAccessService access, KdsAccessService kitchen) {
        this.limiter = limiter; this.ips = ips; this.orders = orders; this.access = access; this.kitchen = kitchen;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type target, Class<? extends HttpMessageConverter<?>> converter) {
        return (parameter.getContainingClass() == OrderController.class && "addCustomerMessage".equals(parameter.getMethod().getName()))
                || (parameter.getContainingClass() == KdsController.class && "addKdsMessage".equals(parameter.getMethod().getName()));
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type target,
            Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        if (input.getHeaders().getContentLength() > OrderMessageRateLimiter.MAX_REQUEST_BYTES) throw tooLarge();
        // Bound the actual stream as well: Content-Length may be absent (chunked transfer).
        byte[] payload = input.getBody().readNBytes(OrderMessageRateLimiter.MAX_REQUEST_BYTES + 1);
        if (payload.length > OrderMessageRateLimiter.MAX_REQUEST_BYTES) throw tooLarge();
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        check(limiter.consumeIp(ips.resolve(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), request.getHeader("X-Real-IP"))));
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(payload); }
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type target,
            Class<? extends HttpMessageConverter<?>> converter) {
        Object value = body instanceof Map<?, ?> map ? map.get("text") : null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > OrderMessageRateLimiter.MAX_TEXT_LENGTH)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must contain between 1 and 2,000 characters.");
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var variables = (Map<?, ?>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String id = (String) variables.get("id");
        boolean isKitchen = parameter.getContainingClass() == KdsController.class;
        if (isKitchen) {
            if (!kitchen.isRequestAllowed(request)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } else {
            String authorization = request.getHeader("Authorization");
            String tracking = request.getHeader(OrderAccessService.TRACKING_TOKEN_HEADER);
            boolean allowed = orders.findById(id).filter(order -> orders.usesPostgres()
                    ? access.canAccessPostgresOrder(order, authorization, tracking)
                    : access.canAccess(order, authorization, tracking)).isPresent();
            if (!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Check ownership before spending the conversation's quota. The store checks
        // authorization again under its write lock before persisting the message.
        check(limiter.consumeOrder(id, isKitchen));
        return body;
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Message request must not exceed 16 KB.");
    }

    private static void check(OptionalLong retry) {
        if (retry.isEmpty()) return;
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many messages. Please wait before sending again.") {
            @Override public HttpHeaders getHeaders() {
                var headers = new HttpHeaders();
                headers.set("Retry-After", Long.toString(retry.getAsLong()));
                return headers;
            }
        };
    }
}
