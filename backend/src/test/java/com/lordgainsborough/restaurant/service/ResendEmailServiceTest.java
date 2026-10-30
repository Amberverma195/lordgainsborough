package com.lordgainsborough.restaurant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ResendEmailServiceTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private ResendEmailService resendEmailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String API_KEY = "re_test_mock_secret_key_12345";
    private static final String API_URL = "https://api.resend.com/emails";
    private static final String FROM_EMAIL = "no-reply@lordgainsboroughrestaurant.ca";
    private static final String FROM_NAME = "Lord Gainsborough";

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        resendEmailService = new ResendEmailService(
                restTemplate,
                API_KEY,
                FROM_EMAIL,
                FROM_NAME,
                API_URL
        );
    }

    @Test
    void testSuccessfulSend_SignupOtpAcceptance() {
        mockServer.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header("Idempotency-Key", "op-signup-1"))
                .andExpect(jsonPath("$.from").value("Lord Gainsborough <no-reply@lordgainsboroughrestaurant.ca>"))
                .andExpect(jsonPath("$.to[0]").value("member@example.com"))
                .andExpect(jsonPath("$.subject").value("Lord Gainsborough - Your Verification Code"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("123456")))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("123456")))
                .andRespond(withSuccess("{\"id\": \"msg_signup_123\"}", MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> resendEmailService.sendEmail(
                "member@example.com",
                "Lord Gainsborough - Your Verification Code",
                "<p>123456</p>",
                "Code: 123456",
                "op-signup-1"
        ));

        mockServer.verify();
    }

    @Test
    void testSuccessfulSend_OtpResendAcceptance() {
        mockServer.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Idempotency-Key", "op-resend-2"))
                .andExpect(jsonPath("$.to[0]").value("member@example.com"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("654321")))
                .andRespond(withSuccess("{\"id\": \"msg_resend_456\"}", MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> resendEmailService.sendEmail(
                "member@example.com",
                "Lord Gainsborough - Your Verification Code",
                "<p>654321</p>",
                "Code: 654321",
                "op-resend-2"
        ));

        mockServer.verify();
    }

    @Test
    void testSuccessfulSend_PasswordResetEmailAcceptance() {
        mockServer.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Idempotency-Key", "reset-op-101"))
                .andExpect(jsonPath("$.from").value("Lord Gainsborough <no-reply@lordgainsboroughrestaurant.ca>"))
                .andExpect(jsonPath("$.to[0]").value("reset@example.com"))
                .andExpect(jsonPath("$.subject").value("Lord Gainsborough - Password Reset Code"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("987654")))
                .andExpect(jsonPath("$.html").value(org.hamcrest.Matchers.containsString("987654")))
                .andRespond(withSuccess("{\"id\": \"msg_reset_789\"}", MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> resendEmailService.sendEmail(
                "reset@example.com",
                "Lord Gainsborough - Password Reset Code",
                "<p>987654</p>",
                "Password reset: 987654",
                "reset-op-101"
        ));

        mockServer.verify();
    }

    @Test
    void testSenderFormatting_WithAndWithoutDisplayName() {
        // With display name
        mockServer.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.from").value("Lord Gainsborough <no-reply@lordgainsboroughrestaurant.ca>"))
                .andRespond(withSuccess("{\"id\": \"1\"}", MediaType.APPLICATION_JSON));

        resendEmailService.sendEmail("to@test.com", "Sub", "<p>hi</p>", "hi", "op-1");
        mockServer.verify();

        // Without display name (blank)
        mockServer.reset();
        resendEmailService.setFromName("");
        mockServer.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.from").value("no-reply@lordgainsboroughrestaurant.ca"))
                .andRespond(withSuccess("{\"id\": \"2\"}", MediaType.APPLICATION_JSON));

        resendEmailService.sendEmail("to@test.com", "Sub", "<p>hi</p>", "hi", "op-2");
        mockServer.verify();

        // When fromEmail already has display format
        mockServer.reset();
        resendEmailService.setFromEmail("Custom Name <custom@example.com>");
        mockServer.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.from").value("Custom Name <custom@example.com>"))
                .andRespond(withSuccess("{\"id\": \"3\"}", MediaType.APPLICATION_JSON));

        resendEmailService.sendEmail("to@test.com", "Sub", "<p>hi</p>", "hi", "op-3");
        mockServer.verify();
    }

    @Test
    void testRecipientFormatting_AsJsonArray() {
        mockServer.expect(requestTo(API_URL))
                .andExpect(request -> {
                    String body = request.getBody().toString();
                    JsonNode node = objectMapper.readTree(body);
                    assertTrue(node.get("to").isArray());
                    assertEquals(1, node.get("to").size());
                    assertEquals("recipient@example.com", node.get("to").get(0).asText());
                })
                .andRespond(withSuccess("{\"id\": \"1\"}", MediaType.APPLICATION_JSON));

        resendEmailService.sendEmail("recipient@example.com", "Sub", "<p>hi</p>", "hi", "op-1");
        mockServer.verify();
    }

    @Test
    void testAuthorizationHeader_WithoutLeakingSecretInExceptions() {
        mockServer.expect(requestTo(API_URL))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\": \"Invalid API key\"}"));

        DefiniteEmailRejectionException ex = assertThrows(DefiniteEmailRejectionException.class, () ->
                resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-auth"));

        // Crucial security verification: The exception message MUST NEVER leak the API key or secret
        assertFalse(ex.getMessage().contains(API_KEY));
        assertFalse(ex.getMessage().contains("re_test"));
        assertTrue(ex.getMessage().contains("HTTP_401"));
        mockServer.verify();
    }

    @Test
    void testIdempotencyKeyHeader_SentWhenPresent_OmittedWhenNullOrBlank() {
        // Present
        mockServer.expect(requestTo(API_URL))
                .andExpect(header("Idempotency-Key", "custom-idempotency-key-42"))
                .andRespond(withSuccess("{\"id\": \"1\"}", MediaType.APPLICATION_JSON));
        resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "custom-idempotency-key-42");
        mockServer.verify();

        // Null
        mockServer.reset();
        mockServer.expect(requestTo(API_URL))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess("{\"id\": \"2\"}", MediaType.APPLICATION_JSON));
        resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", null);
        mockServer.verify();

        // Blank
        mockServer.reset();
        mockServer.expect(requestTo(API_URL))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess("{\"id\": \"3\"}", MediaType.APPLICATION_JSON));
        resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "   ");
        mockServer.verify();
    }

    @Test
    void testHttp401And403_ThrowsDefiniteEmailRejectionException() {
        // 401
        mockServer.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThrows(DefiniteEmailRejectionException.class, () ->
                resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-401"));
        mockServer.verify();

        // 403
        mockServer.reset();
        mockServer.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThrows(DefiniteEmailRejectionException.class, () ->
                resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-403"));
        mockServer.verify();
    }

    @Test
    void testHttp409IdempotencyConflict_ThrowsDefiniteEmailRejectionException() {
        mockServer.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("{\"message\": \"Idempotency conflict\"}"));

        DefiniteEmailRejectionException ex = assertThrows(DefiniteEmailRejectionException.class, () ->
                resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-conflict"));

        assertTrue(ex.getMessage().contains("HTTP_409"));
        mockServer.verify();
    }

    @Test
    void testHttp429And5xx_ThrowsAmbiguousEmailDeliveryException() {
        // 429 Rate Limiting
        mockServer.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThrows(AmbiguousEmailDeliveryException.class, () ->
                resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-429"));
        mockServer.verify();

        // 500 Internal Server Error
        mockServer.reset();
        mockServer.expect(requestTo(API_URL))
                .andRespond(withServerError());
        assertThrows(AmbiguousEmailDeliveryException.class, () ->
                resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-500"));
        mockServer.verify();

        // 503 Service Unavailable
        mockServer.reset();
        mockServer.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(AmbiguousEmailDeliveryException.class, () ->
                resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-503"));
        mockServer.verify();
    }

    @Test
    void testUnverifiedSenderOrDomain_400Or422_ThrowsDefiniteEmailRejectionException() {
        // 400 Bad Request
        mockServer.expect(requestTo(API_URL))
                .andRespond(withBadRequest());
        assertThrows(DefiniteEmailRejectionException.class, () ->
                resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-400"));
        mockServer.verify();

        // 422 Unprocessable Entity (e.g. unverified sender domain)
        mockServer.reset();
        mockServer.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThrows(DefiniteEmailRejectionException.class, () ->
                resendEmailService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-422"));
        mockServer.verify();
    }

    @Test
    void testNetworkTimeout_ThrowsAmbiguousEmailDeliveryException() {
        RestTemplate mockRestTemplate = org.mockito.Mockito.mock(RestTemplate.class);
        ResendEmailService timeoutService = new ResendEmailService(
                mockRestTemplate, API_KEY, FROM_EMAIL, FROM_NAME, API_URL);

        SocketTimeoutException socketTimeout = new SocketTimeoutException("connect timed out");
        ResourceAccessException accessException = new ResourceAccessException("Timeout connecting to Resend", socketTimeout);
        org.mockito.Mockito.when(mockRestTemplate.postForEntity(org.mockito.Mockito.anyString(), org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
                .thenThrow(accessException);

        AmbiguousEmailDeliveryException ex = assertThrows(AmbiguousEmailDeliveryException.class, () ->
                timeoutService.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-timeout"));

        assertTrue(ex.getMessage().contains("delivery status unknown"));
    }

    @Test
    void testMissingApiKey_ThrowsDefiniteEmailRejectionException() {
        ResendEmailService unconfigured = new ResendEmailService(
                restTemplate, "", FROM_EMAIL, FROM_NAME, API_URL);

        assertFalse(unconfigured.isConfigured());

        DefiniteEmailRejectionException ex = assertThrows(DefiniteEmailRejectionException.class, () ->
                unconfigured.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-nokey"));

        assertTrue(ex.getMessage().contains("Resend API key is not configured"));

        // Null API key
        unconfigured.setApiKey(null);
        assertFalse(unconfigured.isConfigured());
        assertThrows(DefiniteEmailRejectionException.class, () ->
                unconfigured.sendEmail("user@example.com", "Sub", "<p>hi</p>", "hi", "op-nullkey"));
    }

    @Test
    void testMissingRecipient_ThrowsDefiniteEmailRejectionException() {
        assertThrows(DefiniteEmailRejectionException.class, () ->
                resendEmailService.sendEmail("", "Sub", "<p>hi</p>", "hi", "op-empty"));

        assertThrows(DefiniteEmailRejectionException.class, () ->
                resendEmailService.sendEmail(null, "Sub", "<p>hi</p>", "hi", "op-null"));
    }
}
