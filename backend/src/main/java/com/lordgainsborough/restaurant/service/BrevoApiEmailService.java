package com.lordgainsborough.restaurant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BrevoApiEmailService {

    private final RestTemplate restTemplate;

    public BrevoApiEmailService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(8000);
        this.restTemplate = new RestTemplate(factory);
    }

    public BrevoApiEmailService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Value("${brevo.api-key:}")
    private String apiKey;

    @Value("${app.mail.from:lordgainsborough5@gmail.com}")
    private String fromEmail;

    @Value("${app.mail.from-name:Lord Gainsborough}")
    private String fromName;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Sends transactional email via Brevo.
     * When {@code sendOperationId} is non-blank it is forwarded as Brevo's
     * {@code idempotencyKey} header so provider-side deduplication is possible.
     */
    public void sendEmail(
            String toEmail,
            String subject,
            String htmlContent,
            String textContent,
            String sendOperationId) {
        Map<String, Object> sender = new LinkedHashMap<>();
        sender.put("name", fromName);
        sender.put("email", fromEmail);

        Map<String, Object> recipient = Map.of("email", toEmail);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", sender);
        body.put("to", List.of(recipient));
        body.put("subject", subject);
        body.put("htmlContent", htmlContent);
        body.put("textContent", textContent);

        HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sendOperationId != null && !sendOperationId.isBlank()) {
            headers.set("idempotencyKey", sendOperationId);
        }

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://api.brevo.com/v3/smtp/email",
                    new HttpEntity<>(body, headers),
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw classifyHttpFailure(response.getStatusCode(), null);
            }
        } catch (HttpStatusCodeException e) {
            // Never retain the provider response body in an exception: it may echo recipient/content.
            throw classifyHttpFailure(e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new AmbiguousEmailDeliveryException(
                    "Brevo API timed out or connection failed; delivery status unknown", e);
        }
    }

    public void sendEmail(String toEmail, String subject, String htmlContent, String textContent) {
        sendEmail(toEmail, subject, htmlContent, textContent, null);
    }

    private RuntimeException classifyHttpFailure(HttpStatusCode status, Throwable cause) {
        int code = status.value();
        String message = "Brevo provider request failed with category HTTP_" + code;
        if (code == 408 || code == 429 || code >= 500) {
            return cause == null
                    ? new AmbiguousEmailDeliveryException(message)
                    : new AmbiguousEmailDeliveryException(message, cause);
        }
        if (code >= 400 && code < 500) {
            return cause == null
                    ? new DefiniteEmailRejectionException(message)
                    : new DefiniteEmailRejectionException(message, cause);
        }
        return cause == null
                ? new AmbiguousEmailDeliveryException(message)
                : new AmbiguousEmailDeliveryException(message, cause);
    }
}
