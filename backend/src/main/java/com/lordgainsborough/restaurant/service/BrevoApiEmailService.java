package com.lordgainsborough.restaurant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BrevoApiEmailService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${brevo.api-key:}")
    private String apiKey;

    @Value("${app.mail.from:lordgainsborough5@gmail.com}")
    private String fromEmail;

    @Value("${app.mail.from-name:Lord Gainsborough}")
    private String fromName;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public void sendEmail(String toEmail, String subject, String htmlContent, String textContent) {
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

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://api.brevo.com/v3/smtp/email",
                    new HttpEntity<>(body, headers),
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Brevo API returned status " + response.getStatusCode());
            }
        } catch (HttpStatusCodeException e) {
            throw new RuntimeException("Brevo API error: " + e.getResponseBodyAsString(), e);
        }
    }
}
