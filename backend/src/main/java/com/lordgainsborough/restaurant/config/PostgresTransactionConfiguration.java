package com.lordgainsborough.restaurant.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** A single transaction manager and datasource coordinate identity, OTP and business writes. */
@Configuration(proxyBeanMethods=false)
@EnableTransactionManagement(proxyTargetClass=true)
public class PostgresTransactionConfiguration {
    @Bean(name={"transactionManager","orderJpaTransactionManager","cartJpaTransactionManager","reservationJpaTransactionManager"})
    @Primary
    @ConditionalOnMissingBean(name="transactionManager")
    JpaTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
}
