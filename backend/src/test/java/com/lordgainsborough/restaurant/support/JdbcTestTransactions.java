package com.lordgainsborough.restaurant.support;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Transaction resources for focused JDBC store contexts; uses JPA when the fixture includes Hibernate. */
@TestConfiguration(proxyBeanMethods=false)
public class JdbcTestTransactions {
    @Bean PlatformTransactionManager transactionManager(DataSource source,ObjectProvider<EntityManagerFactory> factories) {
        var factory=factories.getIfAvailable();
        return factory==null?new DataSourceTransactionManager(source):new JpaTransactionManager(factory);
    }
}
