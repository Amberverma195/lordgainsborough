package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.EmailSenderService;
import com.lordgainsborough.restaurant.service.ResendEmailService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ResendConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ResendEmailService.class, EmailSenderService.class);

    @Test
    void testStartupWithValidResendConfiguration() {
        contextRunner
                .withPropertyValues(
                        "resend.api-key=re_valid_test_key_123",
                        "app.mail.from=no-reply@lordgainsboroughrestaurant.ca",
                        "app.mail.from-name=Lord Gainsborough",
                        "resend.api-url=https://api.resend.com/emails",
                        "resend.connect-timeout-ms=4000",
                        "resend.read-timeout-ms=6000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ResendEmailService.class);
                    assertThat(context).hasSingleBean(EmailSenderService.class);

                    ResendEmailService resendService = context.getBean(ResendEmailService.class);
                    assertThat(resendService.isConfigured()).isTrue();
                    assertThat(resendService.getFromEmail()).isEqualTo("no-reply@lordgainsboroughrestaurant.ca");
                    assertThat(resendService.getFromName()).isEqualTo("Lord Gainsborough");
                    assertThat(resendService.getApiUrl()).isEqualTo("https://api.resend.com/emails");

                    // Verify configured timeouts are bound to service and HTTP client
                    assertThat(resendService.getConnectTimeoutMs()).isEqualTo(4000);
                    assertThat(resendService.getReadTimeoutMs()).isEqualTo(6000);

                    ClientHttpRequestFactory requestFactory = resendService.getRestTemplate().getRequestFactory();
                    assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
                    assertThat((int) ReflectionTestUtils.getField(requestFactory, "connectTimeout")).isEqualTo(4000);
                    assertThat((int) ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(6000);

                    EmailSenderService senderService = context.getBean(EmailSenderService.class);
                    assertThat(senderService.isConfigured()).isTrue();
                });
    }

    @Test
    void testStartupWithoutApiKey_FailsSafelyWithoutContextCrash() {
        contextRunner
                .withPropertyValues(
                        "resend.api-key=",
                        "app.mail.from=no-reply@lordgainsboroughrestaurant.ca",
                        "app.mail.from-name=Lord Gainsborough"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ResendEmailService.class);
                    assertThat(context).hasSingleBean(EmailSenderService.class);

                    ResendEmailService resendService = context.getBean(ResendEmailService.class);
                    assertThat(resendService.isConfigured()).isFalse();

                    // Verify default timeouts (5000ms connect, 8000ms read) apply when omitted
                    assertThat(resendService.getConnectTimeoutMs()).isEqualTo(5000);
                    assertThat(resendService.getReadTimeoutMs()).isEqualTo(8000);

                    ClientHttpRequestFactory requestFactory = resendService.getRestTemplate().getRequestFactory();
                    assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
                    assertThat((int) ReflectionTestUtils.getField(requestFactory, "connectTimeout")).isEqualTo(5000);
                    assertThat((int) ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(8000);

                    EmailSenderService senderService = context.getBean(EmailSenderService.class);
                    assertThat(senderService.isConfigured()).isFalse();
                });
    }

    @Test
    void testCustomTimeoutBinding_AppliesEffectiveTimeoutsToHttpClient() {
        contextRunner
                .withPropertyValues(
                        "resend.api-key=re_custom_timeouts",
                        "resend.connect-timeout-ms=2500",
                        "resend.read-timeout-ms=7500"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ResendEmailService resendService = context.getBean(ResendEmailService.class);

                    assertThat(resendService.getConnectTimeoutMs()).isEqualTo(2500);
                    assertThat(resendService.getReadTimeoutMs()).isEqualTo(7500);

                    ClientHttpRequestFactory requestFactory = resendService.getRestTemplate().getRequestFactory();
                    assertThat(requestFactory).isInstanceOf(SimpleClientHttpRequestFactory.class);
                    assertThat((int) ReflectionTestUtils.getField(requestFactory, "connectTimeout")).isEqualTo(2500);
                    assertThat((int) ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(7500);
                });
    }
}
