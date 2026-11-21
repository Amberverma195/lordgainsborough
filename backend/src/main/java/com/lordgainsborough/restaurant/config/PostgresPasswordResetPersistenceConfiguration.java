package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.security.PasswordResetQueuePayloadEncryptor;
import com.lordgainsborough.restaurant.service.AuthSecurityObservability;
import com.lordgainsborough.restaurant.service.EmailSenderService;
import com.lordgainsborough.restaurant.service.PasswordResetEmailDeliveryQueue;
import com.lordgainsborough.restaurant.service.PasswordResetOtpChallengePersistence;
import com.lordgainsborough.restaurant.service.PasswordResetOtpChallengeStore;
import com.lordgainsborough.restaurant.service.PostgresPasswordResetEmailDeliveryQueue;
import com.lordgainsborough.restaurant.service.PostgresPasswordResetOtpChallengeStore;
import com.lordgainsborough.restaurant.service.PasswordResetQueueWorker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/** PostgreSQL persistence for the application. */
@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(PostgresTransactionConfiguration.class)
@EnableTransactionManagement(proxyTargetClass = true)
@EnableScheduling
public class PostgresPasswordResetPersistenceConfiguration {

    @Bean
    public PasswordResetQueueWorker passwordResetQueueWorker(
            PasswordResetEmailDeliveryQueue queue) {
        return new PasswordResetQueueWorker(queue);
    }

    @Bean(name = "postgresPasswordResetOtpChallengeStore")
    @Primary
    public PasswordResetOtpChallengeStore postgresPasswordResetOtpChallengeStore(
            DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            PasswordResetProperties properties) {
        return new PostgresPasswordResetOtpChallengeStore(dataSource, transactionManager, properties);
    }

    @Bean(name = "passwordResetQueuePayloadEncryptor")
    @Primary
    public PasswordResetQueuePayloadEncryptor passwordResetQueuePayloadEncryptor(PasswordResetProperties properties) {
        PasswordResetQueuePayloadEncryptor encryptor = new PasswordResetQueuePayloadEncryptor(properties);
        if (encryptor.isEphemeralKey()) {
            throw new IllegalStateException("Durable password reset delivery requires PASSWORD_RESET_QUEUE_ENCRYPTION_KEY "
                    + "(app.auth.password-reset.queue-encryption-key), shared across restarts and instances");
        }
        return encryptor;
    }

    @Bean(name = "passwordResetEmailDeliveryQueue")
    @Primary
    public PasswordResetEmailDeliveryQueue passwordResetEmailDeliveryQueue(
            DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            ObjectProvider<EmailSenderService> emailSenderServiceProvider,
            ObjectProvider<PasswordResetOtpChallengePersistence> persistenceProvider,
            PasswordResetProperties properties,
            ObjectProvider<AuthSecurityObservability> observabilityProvider,
            ObjectProvider<PasswordResetQueuePayloadEncryptor> encryptorProvider) {
        return new PostgresPasswordResetEmailDeliveryQueue(
                dataSource,
                transactionManager,
                emailSenderServiceProvider.getIfAvailable(),
                persistenceProvider.getIfAvailable(),
                properties,
                observabilityProvider.getIfAvailable(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                encryptorProvider.getIfAvailable(() -> new PasswordResetQueuePayloadEncryptor(properties)));
    }

}
