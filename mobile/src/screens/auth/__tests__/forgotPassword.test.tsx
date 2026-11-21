import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react-native';
import { router } from 'expo-router';
import React from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

jest.mock('expo-crypto', () => ({
  randomUUID: () => 'reset-uuid-5678',
}));

jest.mock('expo-router', () => ({
  useFocusEffect: (callback: () => void) =>
    require('react').useEffect(callback, []),
  router: {
    push: jest.fn(),
    replace: jest.fn(),
    back: jest.fn(),
    canGoBack: jest.fn().mockReturnValue(true),
  },
}));

jest.mock('../../../api/auth', () => ({
  sendPasswordResetOtp: jest.fn(),
  resendPasswordResetOtp: jest.fn(),
  completePasswordReset: jest.fn(),
}));

import * as authApi from '../../../api/auth';
import { ApiError } from '../../../api/errors';
import ForgotPasswordScreen from '../../../app/auth/forgot-password';

const mockSendPasswordResetOtp = authApi.sendPasswordResetOtp as jest.Mock;
const mockResendPasswordResetOtp = authApi.resendPasswordResetOtp as jest.Mock;
const mockCompletePasswordReset = authApi.completePasswordReset as jest.Mock;

const initialMetrics = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

describe('ForgotPasswordScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(async () => {
    await act(async () => {
      cleanup();
    });
  });

  const renderScreen = () =>
    render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <ForgotPasswordScreen />
      </SafeAreaProvider>,
    );

  it('renders Step 1 and navigates back when requested', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByText('Reset password')).toBeTruthy();
      expect(
        screen.getByLabelText('Email address or phone number'),
      ).toBeTruthy();
      expect(screen.getByText('Send reset code')).toBeTruthy();
      expect(screen.getByText('Back to sign in')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Back to sign in'));
    });
    expect(router.back).toHaveBeenCalled();
  });

  it('validates email format in Step 1', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByText('Send reset code')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('Email address or phone number'),
        'invalid-email',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByText('Send reset code'));
    });

    await waitFor(() => {
      expect(
        screen.getByText(
          'Please enter a valid email or Canadian/US phone number.',
        ),
      ).toBeTruthy();
    });
    expect(mockSendPasswordResetOtp).not.toHaveBeenCalled();
  });

  it('submits valid email in Step 1 and advances to Step 2', async () => {
    mockSendPasswordResetOtp.mockResolvedValueOnce({
      code: 'RESET_OTP_SENT',
      message: 'If an account matches that email, a reset code was sent.',
      challengeId: 'reset-chal-999',
      nextResendAt: null,
      retryAfterSeconds: 60,
    });

    renderScreen();

    await waitFor(() => {
      expect(
        screen.getByLabelText('Email address or phone number'),
      ).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('Email address or phone number'),
        'customer@example.com',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByText('Send reset code'));
    });

    await waitFor(() => {
      expect(mockSendPasswordResetOtp).toHaveBeenCalledWith(
        'customer@example.com',
        'reset-uuid-5678',
      );
      expect(screen.getByLabelText('6-digit verification code')).toBeTruthy();
      expect(screen.getByLabelText('New password')).toBeTruthy();
      expect(screen.getByLabelText('Confirm new password')).toBeTruthy();
      expect(screen.getByLabelText('Reset password')).toBeTruthy();
    });
  });

  it('validates password match and complexity in Step 2', async () => {
    mockSendPasswordResetOtp.mockResolvedValueOnce({
      code: 'RESET_OTP_SENT',
      message: 'Reset code sent.',
      challengeId: 'reset-chal-999',
      nextResendAt: null,
      retryAfterSeconds: 60,
    });

    renderScreen();

    await waitFor(() => {
      expect(
        screen.getByLabelText('Email address or phone number'),
      ).toBeTruthy();
    });

    // Advance to Step 2
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('Email address or phone number'),
        'customer@example.com',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByText('Send reset code'));
    });

    await waitFor(() => {
      expect(screen.getByLabelText('6-digit verification code')).toBeTruthy();
    });

    // Short OTP
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('6-digit verification code'),
        '12',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Reset password'));
    });
    await waitFor(() => {
      expect(
        screen.getByText('Please enter the 6-digit verification code.'),
      ).toBeTruthy();
    });

    // Weak password
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('6-digit verification code'),
        '123456',
      );
      fireEvent.changeText(screen.getByLabelText('New password'), 'short');
      fireEvent.changeText(
        screen.getByLabelText('Confirm new password'),
        'short',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Reset password'));
    });
    await waitFor(() => {
      expect(
        screen.getByText('Password must be at least 8 characters long.'),
      ).toBeTruthy();
    });

    // Password mismatch
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('New password'),
        'NewPassword123!',
      );
      fireEvent.changeText(
        screen.getByLabelText('Confirm new password'),
        'DifferentPassword123!',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Reset password'));
    });
    await waitFor(() => {
      expect(screen.getByText('Passwords do not match.')).toBeTruthy();
    });
  });

  it('completes password reset and displays success screen (Step 3)', async () => {
    mockSendPasswordResetOtp.mockResolvedValueOnce({
      code: 'RESET_OTP_SENT',
      message: 'Reset code sent.',
      challengeId: 'reset-chal-999',
      nextResendAt: null,
      retryAfterSeconds: 60,
    });
    mockCompletePasswordReset.mockResolvedValueOnce(undefined);

    renderScreen();

    await waitFor(() => {
      expect(
        screen.getByLabelText('Email address or phone number'),
      ).toBeTruthy();
    });

    // Advance to Step 2
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('Email address or phone number'),
        'customer@example.com',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByText('Send reset code'));
    });

    await waitFor(() => {
      expect(screen.getByLabelText('6-digit verification code')).toBeTruthy();
    });

    // Fill valid reset data
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('6-digit verification code'),
        '654321',
      );
    });
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('New password'),
        'BrandNewPass123!',
      );
    });
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('Confirm new password'),
        'BrandNewPass123!',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Reset password'));
    });

    // Step 3 complete screen
    await waitFor(() => {
      expect(mockCompletePasswordReset).toHaveBeenCalledWith({
        email: 'customer@example.com',
        challengeId: 'reset-chal-999',
        otp: '654321',
        password: 'BrandNewPass123!',
        idempotencyKey: 'reset-uuid-5678',
      });
      expect(screen.getByText('Password updated')).toBeTruthy();
      expect(screen.getByText('Sign in')).toBeTruthy();
    });

    // Navigate to Sign in
    await act(async () => {
      fireEvent.press(screen.getByText('Sign in'));
    });
    expect(router.replace).toHaveBeenCalledWith('/auth/login');
  });

  it('displays API error when password reset fails', async () => {
    mockSendPasswordResetOtp.mockResolvedValueOnce({
      code: 'RESET_OTP_SENT',
      message: 'Reset code sent.',
      challengeId: 'reset-chal-999',
      nextResendAt: null,
      retryAfterSeconds: 60,
    });
    mockCompletePasswordReset.mockRejectedValueOnce(
      new ApiError({
        status: 400,
        code: 'OTP_EXPIRED',
        message: 'This reset code has expired. Please request a new code.',
      }),
    );

    renderScreen();

    await waitFor(() => {
      expect(
        screen.getByLabelText('Email address or phone number'),
      ).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('Email address or phone number'),
        'customer@example.com',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByText('Send reset code'));
    });

    await waitFor(() => {
      expect(screen.getByLabelText('6-digit verification code')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('6-digit verification code'),
        '111111',
      );
    });
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('New password'),
        'BrandNewPass123!',
      );
    });
    await act(async () => {
      fireEvent.changeText(
        screen.getByLabelText('Confirm new password'),
        'BrandNewPass123!',
      );
    });
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Reset password'));
    });

    await waitFor(() => {
      expect(
        screen.getByText(
          'This reset code has expired. Please request a new code.',
        ),
      ).toBeTruthy();
    });
  });
});

it('accepts a Canadian phone and uses server deadlines and resend quotas', async () => {
  mockSendPasswordResetOtp.mockResolvedValueOnce({
    code: 'RESET_CHALLENGE_REUSED',
    message: 'Use the existing code.',
    challengeId: 'phone-reset',
    serverTime: new Date().toISOString(),
    nextResendAt: new Date(Date.now() + 30000).toISOString(),
    challengeExpiresAt: new Date(Date.now() + 600000).toISOString(),
    resendsRemaining: 2,
  });
  await render(
    <SafeAreaProvider initialMetrics={initialMetrics}>
      <ForgotPasswordScreen />
    </SafeAreaProvider>,
  );
  await fireEvent.changeText(
    screen.getByLabelText('Email address or phone number'),
    '+1 (519) 555-0100',
  );
  await fireEvent.press(screen.getByText('Send reset code'));
  await waitFor(() =>
    expect(screen.getByText('2 resends remaining')).toBeTruthy(),
  );
  expect(mockSendPasswordResetOtp).toHaveBeenCalledWith(
    '5195550100',
    'reset-uuid-5678',
  );
  expect(screen.getByText(/Resend code in/)).toBeTruthy();
  expect(screen.getByText('Use the existing code.')).toBeTruthy();
});
it('offers Start New Reset after the authoritative challenge deadline', async () => {
  mockSendPasswordResetOtp.mockResolvedValueOnce({
    code: 'RESET_CODE_SENT',
    challengeId: 'expired',
    serverTime: new Date().toISOString(),
    challengeExpiresAt: new Date(Date.now() - 1000).toISOString(),
    resendsRemaining: 0,
  });
  await render(
    <SafeAreaProvider initialMetrics={initialMetrics}>
      <ForgotPasswordScreen />
    </SafeAreaProvider>,
  );
  await fireEvent.changeText(
    screen.getByLabelText('Email address or phone number'),
    'member@example.com',
  );
  await fireEvent.press(screen.getByText('Send reset code'));
  await waitFor(() => expect(screen.getByText('Start New Reset')).toBeTruthy());
  await fireEvent.press(screen.getByText('Start New Reset'));
  expect(screen.getByText('Send reset code')).toBeTruthy();
});
