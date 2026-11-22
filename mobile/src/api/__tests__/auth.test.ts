import {
  completePasswordReset,
  deleteAccount,
  logout,
  resendPasswordResetOtp,
  sendPasswordResetOtp,
  sendSignupOtp,
  updateProfile,
  verifySignup,
} from '../auth';
import { apiRequest } from '../client';
import { ApiError } from '../errors';

jest.mock('../client', () => ({
  apiRequest: jest.fn(),
  onUnauthorized: jest.fn(),
}));

const mockApiRequest = apiRequest as jest.Mock;

describe('auth API', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('updateProfile', () => {
    it('calls PUT /api/auth/profile and returns updated SessionUser', async () => {
      mockApiRequest.mockResolvedValueOnce({
        status: 200,
        body: {
          token: 'new-jwt-token',
          name: 'Jane Smith',
          email: 'jane@example.com',
          phone: '5195550199',
          role: 'CUSTOMER',
        },
      });

      const user = await updateProfile('old-token', '  Jane Smith  ');
      expect(mockApiRequest).toHaveBeenCalledWith('/api/auth/profile', {
        method: 'PUT',
        token: 'old-token',
        body: { name: 'Jane Smith' },
      });
      expect(user.token).toBe('new-jwt-token');
      expect(user.name).toBe('Jane Smith');
      expect(user.email).toBe('jane@example.com');
    });

    it('throws ApiError when response cannot be parsed', async () => {
      mockApiRequest.mockResolvedValueOnce({
        status: 200,
        body: null,
      });

      await expect(updateProfile('token', 'Jane')).rejects.toThrow(ApiError);
    });
  });

  describe('sendSignupOtp', () => {
    it('calls POST /api/auth/signup/send-otp and parses response', async () => {
      mockApiRequest.mockResolvedValueOnce({
        status: 202,
        body: {
          code: 'OTP_SENT',
          message: 'Code sent to email',
          challengeId: 'chal-123',
          attemptToken: 'att-456',
          nextResendAt: '2026-11-16T12:00:00Z',
          resendsRemaining: 3,
        },
      });

      const result = await sendSignupOtp({
        name: 'Jane Doe',
        email: 'jane@example.com',
        phone: '5195550199',
        password: 'Password1!',
        idempotencyKey: 'idem-signup-1',
        termsAccepted: true,
        termsVersion: 'terms-v1.0',
        privacyVersion: 'privacy-v1.1',
      });

      expect(mockApiRequest).toHaveBeenCalledWith(
        '/api/auth/signup/send-otp',
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({
            'Idempotency-Key': 'idem-signup-1',
          }),
          body: expect.objectContaining({
            name: 'Jane Doe',
            email: 'jane@example.com',
            phone: '5195550199',
            password: 'Password1!',
            termsAccepted: true,
            termsVersion: 'terms-v1.0',
            privacyVersion: 'privacy-v1.1',
          }),
        }),
      );
      expect(result.challengeId).toBe('chal-123');
      expect(result.attemptToken).toBe('att-456');
      expect(result.resendsRemaining).toBe(3);
    });
  });

  describe('verifySignup', () => {
    it('calls POST /api/auth/signup and returns session user', async () => {
      mockApiRequest.mockResolvedValueOnce({
        status: 201,
        body: {
          token: 'jwt-signup-token',
          user: {
            name: 'Jane Doe',
            email: 'jane@example.com',
            phone: '5195550199',
            role: 'CUSTOMER',
          },
        },
      });

      const user = await verifySignup({
        name: 'Jane Doe',
        email: 'jane@example.com',
        phone: '5195550199',
        password: 'Password1!',
        otp: '123456',
        challengeId: 'chal-123',
        attemptToken: 'att-456',
        termsAccepted: true,
        termsVersion: 'terms-v1.0',
        privacyVersion: 'privacy-v1.1',
      });

      expect(mockApiRequest).toHaveBeenCalledWith(
        '/api/auth/signup',
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({
            'X-Signup-Attempt-Token': 'att-456',
          }),
          body: expect.objectContaining({
            otp: '123456',
            challengeId: 'chal-123',
            termsAccepted: true,
            termsVersion: 'terms-v1.0',
            privacyVersion: 'privacy-v1.1',
          }),
        }),
      );
      expect(user.token).toBe('jwt-signup-token');
      expect(user.name).toBe('Jane Doe');
    });

    it('throws ApiError if verification response contains an error', async () => {
      mockApiRequest.mockResolvedValueOnce({
        status: 400,
        body: {
          code: 'OTP_INVALID',
          message: 'Invalid or expired OTP',
        },
      });

      await expect(
        verifySignup({
          name: 'Jane Doe',
          email: 'jane@example.com',
          phone: '5195550199',
          password: 'Password1!',
          otp: '000000',
          termsAccepted: true,
          termsVersion: 'terms-v1.0',
          privacyVersion: 'privacy-v1.1',
        }),
      ).rejects.toThrow(ApiError);
    });
  });

  describe('sendPasswordResetOtp and resendPasswordResetOtp', () => {
    it('sends password reset OTP request', async () => {
      mockApiRequest.mockResolvedValueOnce({
        status: 202,
        body: {
          code: 'RESET_OTP_SENT',
          message: 'OTP sent to email',
          challengeId: 'reset-chal-1',
          retryAfterSeconds: 60,
        },
      });

      const res = await sendPasswordResetOtp(
        'user@example.com',
        'idem-reset-1',
      );
      expect(mockApiRequest).toHaveBeenCalledWith(
        '/api/auth/forgot-password/send-otp',
        expect.objectContaining({
          method: 'POST',
          headers: { 'Idempotency-Key': 'idem-reset-1' },
          body: { email: 'user@example.com' },
        }),
      );
      expect(res.challengeId).toBe('reset-chal-1');
      expect(res.retryAfterSeconds).toBe(60);
    });

    it('resends password reset OTP request', async () => {
      mockApiRequest.mockResolvedValueOnce({
        status: 202,
        body: {
          code: 'RESET_OTP_RESENT',
          challengeId: 'reset-chal-1',
        },
      });

      const res = await resendPasswordResetOtp(
        'user@example.com',
        'reset-chal-1',
        'idem-resend-1',
      );
      expect(mockApiRequest).toHaveBeenCalledWith(
        '/api/auth/forgot-password/resend-otp',
        expect.objectContaining({
          method: 'POST',
          headers: { 'Idempotency-Key': 'idem-resend-1' },
          body: { email: 'user@example.com', challengeId: 'reset-chal-1' },
        }),
      );
      expect(res.challengeId).toBe('reset-chal-1');
    });
  });

  describe('completePasswordReset', () => {
    it('completes password reset with new password and OTP', async () => {
      mockApiRequest.mockResolvedValueOnce({
        status: 200,
        body: {
          code: 'PASSWORD_RESET_COMPLETED',
          message: 'Password reset successful',
        },
      });

      await completePasswordReset({
        email: 'user@example.com',
        challengeId: 'chal-reset-9',
        otp: '654321',
        password: 'NewPassword123!',
        idempotencyKey: 'idem-complete-1',
      });

      expect(mockApiRequest).toHaveBeenCalledWith(
        '/api/auth/forgot-password/reset',
        expect.objectContaining({
          method: 'POST',
          headers: { 'Idempotency-Key': 'idem-complete-1' },
          body: {
            email: 'user@example.com',
            challengeId: 'chal-reset-9',
            otp: '654321',
            password: 'NewPassword123!',
          },
        }),
      );
    });
  });
});

it('deletes the authenticated account using current-password confirmation', async () => {
  mockApiRequest.mockResolvedValueOnce({ status: 204, body: null });
  await deleteAccount('bearer-jwt', 'CurrentPassword1!');
  expect(mockApiRequest).toHaveBeenCalledWith('/api/auth/me', {
    method: 'DELETE',
    token: 'bearer-jwt',
    body: { password: 'CurrentPassword1!' },
  });
});

it('revokes this device session through the authenticated logout endpoint', async () => {
  mockApiRequest.mockResolvedValueOnce({ status: 204, body: null });
  await logout('device-token');
  expect(mockApiRequest).toHaveBeenCalledWith('/api/auth/logout', {
    method: 'POST',
    token: 'device-token',
    notifyUnauthorized: false,
  });
});
it('treats an already revoked or expired session as signed out', async () => {
  mockApiRequest.mockRejectedValueOnce(
    new ApiError({ status: 401, code: 'UNAUTHORIZED', message: 'Expired' }),
  );
  await expect(logout('expired')).resolves.toBeUndefined();
});
it('does not claim successful server sign-out after a network failure', async () => {
  mockApiRequest.mockRejectedValueOnce(
    new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'Offline' }),
  );
  await expect(logout('device-token')).rejects.toThrow('Offline');
});
