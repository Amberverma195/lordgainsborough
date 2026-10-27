package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.PasswordResetOtpChallengeStore;
import com.lordgainsborough.restaurant.service.PostgresPasswordResetOtpChallengeStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/** PostgreSQL persistence for the application. */
@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(PostgresTransactionConfiguration.class)
@EnableTransactionManagement(proxyTargetClass = true)
public class PostgresPasswordResetPersistenceConfiguration {

    @Bean(name = "postgresPasswordResetOtpChallengeStore")
    @Primary
    public PasswordResetOtpChallengeStore postgresPasswordResetOtpChallengeStore(
            DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            PasswordResetProperties properties) {
        return new PostgresPasswordResetOtpChallengeStore(dataSource, transactionManager, properties);
    }

}
