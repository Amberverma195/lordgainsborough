package com.lordgainsborough.restaurant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends transactional email exclusively through Resend's HTTPS REST API.
 */
@Service
public class ResendEmailService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${resend.api-key:}")
    private String apiKey;

    @Value("${app.mail.from:no-reply@lordgainsboroughrestaurant.ca}")
    private String fromEmail = "no-reply@lordgainsboroughrestaurant.ca";

    @Value("${app.mail.from-name:Lord Gainsborough}")
    private String fromName = "Lord Gainsborough";

    @Value("${resend.api-url:https://api.resend.com/emails}")
    private String apiUrl = "https://api.resend.com/emails";

    public ResendEmailService() {
        this(5000, 8000);
    }

    public ResendEmailService(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restTemplate = new RestTemplate(factory);
    }

    public ResendEmailService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ResendEmailService(
            RestTemplate restTemplate,
            String apiKey,
            String fromEmail,
            String fromName,
            String apiUrl) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.fromEmail = fromEmail != null ? fromEmail : "no-reply@lordgainsboroughrestaurant.ca";
        this.fromName = fromName != null ? fromName : "Lord Gainsborough";
        this.apiUrl = apiUrl != null ? apiUrl : "https://api.resend.com/emails";
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getFromEmail() {
        return fromEmail;
    }

    public void setFromEmail(String fromEmail) {
        this.fromEmail = fromEmail;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Sends transactional email via Resend HTTPS REST API.
     *
     * @param toEmail recipient email address
     * @param subject email subject line
     * @param htmlContent HTML body
     * @param textContent plain-text body
     * @param sendOperationId optional operation ID forwarded as Resend's Idempotency-Key
     */
    public void sendEmail(
            String toEmail,
            String subject,
            String htmlContent,
            String textContent,
            String sendOperationId) {

        if (!isConfigured()) {
            throw new DefiniteEmailRejectionException("Resend API key is not configured");
        }
        if (toEmail == null || toEmail.isBlank()) {
            throw new DefiniteEmailRejectionException("Recipient email address is required");
        }

        String formattedFrom;
        if (fromEmail.contains("<") && fromEmail.contains(">")) {
            formattedFrom = fromEmail.trim();
        } else if (fromName != null && !fromName.isBlank()) {
            formattedFrom = fromName.trim() + " <" + fromEmail.trim() + ">";
        } else {
            formattedFrom = fromEmail.trim();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", formattedFrom);
        body.put("to", List.of(toEmail.trim()));
        body.put("subject", subject != null ? subject : "");
        body.put("html", htmlContent != null ? htmlContent : "");
        body.put("text", textContent != null ? textContent : "");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey.trim());
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sendOperationId != null && !sendOperationId.isBlank()) {
            headers.set("Idempotency-Key", sendOperationId.trim());
        }

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw classifyHttpFailure(response.getStatusCode(), response.getBody());
            }
        } catch (HttpStatusCodeException e) {
            // Never retain provider response body or cause in the exception or logs: it may echo recipient or payload details.
            throw classifyHttpFailure(e.getStatusCode(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new AmbiguousEmailDeliveryException(
                    "Resend API timed out or connection failed; delivery status unknown", e);
        } catch (EmailDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new AmbiguousEmailDeliveryException(
                    "Resend provider request failed with unknown delivery status", e);
        }
    }

    public void sendEmail(String toEmail, String subject, String htmlContent, String textContent) {
        sendEmail(toEmail, subject, htmlContent, textContent, null);
    }

    private RuntimeException classifyHttpFailure(HttpStatusCode status) {
        return classifyHttpFailure(status, null);
    }

    private RuntimeException classifyHttpFailure(HttpStatusCode status, String responseBody) {
        int code = status.value();
        if (code == 401 || code == 403) {
            return new DefiniteEmailRejectionException("Resend authentication failed: HTTP_" + code);
        }
        if (code == 409) {
            if (isConcurrentIdempotentRequest(responseBody)) {
                return new AmbiguousEmailDeliveryException(
                        "Resend concurrent in-flight request conflict: HTTP_409");
            }
            if (isInvalidIdempotentRequest(responseBody)) {
                return new DefiniteEmailRejectionException(
                        "Resend invalid idempotent request conflict: HTTP_409");
            }
            return new DefiniteEmailRejectionException("Resend idempotency conflict: HTTP_409");
        }
        if (code == 408 || code == 429 || code >= 500) {
            return new AmbiguousEmailDeliveryException("Resend provider error: HTTP_" + code);
        }
        if (code >= 400 && code < 500) {
            return new DefiniteEmailRejectionException("Resend rejected request: HTTP_" + code);
        }
        return new AmbiguousEmailDeliveryException("Resend provider request failed with category HTTP_" + code);
    }

    private boolean isConcurrentIdempotentRequest(String responseBody) {
        return matchesErrorIdentifier(responseBody, "concurrent_idempotent_requests");
    }

    private boolean isInvalidIdempotentRequest(String responseBody) {
        return matchesErrorIdentifier(responseBody, "invalid_idempotent_request");
    }

    private boolean matchesErrorIdentifier(String responseBody, String identifier) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root != null) {
                if (root.hasNonNull("name") && identifier.equalsIgnoreCase(root.get("name").asText().trim())) {
                    return true;
                }
                if (root.hasNonNull("code") && identifier.equalsIgnoreCase(root.get("code").asText().trim())) {
                    return true;
                }
                if (root.hasNonNull("error") && identifier.equalsIgnoreCase(root.get("error").asText().trim())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Non-JSON response, fall through to substring match
        }
        return responseBody.contains(identifier);
    }
}
