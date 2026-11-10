package com.lordgainsborough.restaurant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Account deletion/KDS status only: retry rolled-back transactions, never individual statements. */
final class PostgresDeadlockRetry {
    private static final Logger log = LoggerFactory.getLogger(PostgresDeadlockRetry.class);
    private static final int MAX_ATTEMPTS = 3;
    private PostgresDeadlockRetry() {}

    static <T> T execute(String operation, Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (RuntimeException failure) {
                if (attempt == MAX_ATTEMPTS || !isPostgresDeadlock(failure)) throw failure;
                log.warn("Retrying {} after PostgreSQL deadlock (retry {} of {})", operation, attempt, MAX_ATTEMPTS - 1);
                try {
                    Thread.sleep(20L * attempt + ThreadLocalRandom.current().nextLong(20));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.addSuppressed(interrupted);
                    throw failure;
                }
            }
        }
    }

    private static boolean isPostgresDeadlock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "40P01".equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
