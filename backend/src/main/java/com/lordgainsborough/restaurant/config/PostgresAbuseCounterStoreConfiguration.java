package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.service.PostgresAbuseCounterStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/** PostgreSQL persistence for the application. */
@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(PostgresTransactionConfiguration.class)
@EnableTransactionManagement(proxyTargetClass = true)
public class PostgresAbuseCounterStoreConfiguration {

    @Bean(name = {"postgresAbuseCounterStore", "abuseCounterStore"})
    @Primary
    public PostgresAbuseCounterStore postgresAbuseCounterStore(DataSource dataSource) {
        return new PostgresAbuseCounterStore(dataSource);
    }

}
