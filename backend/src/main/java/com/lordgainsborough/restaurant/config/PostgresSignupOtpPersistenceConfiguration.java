package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.PostgresSignupOtpChallengeStore;
import com.lordgainsborough.restaurant.service.SignupOtpChallengeStore;
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
public class PostgresSignupOtpPersistenceConfiguration {

    @Bean(name = "postgresSignupOtpChallengeStore")
    @Primary
    public SignupOtpChallengeStore postgresSignupOtpChallengeStore(
            DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        return new PostgresSignupOtpChallengeStore(dataSource, transactionManager);
    }

}
