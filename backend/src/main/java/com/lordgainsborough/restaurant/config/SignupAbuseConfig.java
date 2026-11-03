package com.lordgainsborough.restaurant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        SignupAbuseProperties.class,
        LoginRateLimitProperties.class,
        PasswordResetProperties.class,
        ReservationRateLimitProperties.class,
        OrderRateLimitProperties.class
})
public class SignupAbuseConfig {
}
