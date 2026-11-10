package com.lordgainsborough.restaurant.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PostgresDeadlockRetryTest {
    RuntimeException databaseFailure(String state) {
        return new DataAccessResourceFailureException("Database operation failed", new SQLException("fixture",state));
    }

    @Test void retriesOnlyPostgresDeadlocksAndReturnsOnlyTheCommittedResult() {
        var attempts=new AtomicInteger();
        String result=PostgresDeadlockRetry.execute("account deletion",()->{
            if(attempts.incrementAndGet()==1) throw databaseFailure("40P01");
            return "committed";
        });
        assertThat(result).isEqualTo("committed");assertThat(attempts.get()).isEqualTo(2);
    }

    @Test void stopsAfterThreeAttemptsAndPreservesTheDatabaseFailure() {
        var attempts=new AtomicInteger();var failure=databaseFailure("40P01");
        assertThatThrownBy(()->PostgresDeadlockRetry.execute("account deletion",()->{attempts.incrementAndGet();throw failure;})).isSameAs(failure);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test void doesNotRetryBusinessErrorsConstraintFailuresTimeoutsOrSerializationFailures() {
        for(RuntimeException failure:new RuntimeException[]{new UserAuthStore.ActiveOrdersException(),databaseFailure("23505"),
                databaseFailure("55P03"),databaseFailure("40001"),new IllegalStateException("fixture")}) {
            var attempts=new AtomicInteger();
            assertThatThrownBy(()->PostgresDeadlockRetry.execute("account deletion",()->{attempts.incrementAndGet();throw failure;})).isSameAs(failure);
            assertThat(attempts.get()).isEqualTo(1);
        }
    }

    @Test void interruptionStopsRetriesAndPreservesTheInterruptFlag() {
        var attempts=new AtomicInteger();var failure=databaseFailure("40P01");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->PostgresDeadlockRetry.execute("account deletion",()->{attempts.incrementAndGet();throw failure;})).isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(attempts.get()).isEqualTo(1);
        } finally {Thread.interrupted();}
    }
}
