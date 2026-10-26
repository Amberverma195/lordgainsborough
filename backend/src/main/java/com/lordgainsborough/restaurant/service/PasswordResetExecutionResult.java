package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.PasswordResetOtpChallenge;

/**
 * Result of an atomic password reset transaction.
 */
public record PasswordResetExecutionResult(
        Status status,
        PasswordResetOtpChallenge challenge,
        String failureReason
) {
    public enum Status {
        SUCCESS,
        ALREADY_COMPLETED,
        INVALID_CHALLENGE,
        CHALLENGE_LOCKED,
        CODE_EXPIRED,
        CHALLENGE_EXPIRED,
        USER_NOT_FOUND,
        USER_UPDATE_FAILED,
        TRANSACTION_FAILED
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isAlreadyCompleted() {
        return status == Status.ALREADY_COMPLETED;
    }

    public static PasswordResetExecutionResult success(PasswordResetOtpChallenge challenge) {
        return new PasswordResetExecutionResult(Status.SUCCESS, challenge, null);
    }

    public static PasswordResetExecutionResult alreadyCompleted(PasswordResetOtpChallenge challenge) {
        return new PasswordResetExecutionResult(Status.ALREADY_COMPLETED, challenge, null);
    }

    public static PasswordResetExecutionResult failure(Status status, String reason) {
        return new PasswordResetExecutionResult(status, null, reason);
    }
}
