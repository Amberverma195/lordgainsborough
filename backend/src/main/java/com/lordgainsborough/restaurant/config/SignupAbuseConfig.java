package com.lordgainsborough.restaurant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SignupAbuseProperties.class)
public class SignupAbuseConfig {
}
