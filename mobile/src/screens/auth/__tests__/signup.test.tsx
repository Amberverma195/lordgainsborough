import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { router } from 'expo-router';
import React from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

jest.mock('expo-crypto', () => ({
  randomUUID: () => 'signup-uuid-1234',
}));

jest.mock('expo-router', () => ({
  router: {
    push: jest.fn(),
    replace: jest.fn(),
    back: jest.fn(),
    canGoBack: jest.fn().mockReturnValue(true),
  },
}));

jest.mock('../../../api/auth', () => ({
  sendSignupOtp: jest.fn(),
  verifySignup: jest.fn(),
}));

jest.mock('../../../auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

import * as authApi from '../../../api/auth';
import { ApiError } from '../../../api/errors';
import { SessionUser } from '../../../api/parse';
import { useAuth } from '../../../auth/AuthContext';
import SignupScreen from '../../../app/auth/signup';

const mockUseAuth = useAuth as jest.Mock;
const mockSendSignupOtp = authApi.sendSignupOtp as jest.Mock;
const mockVerifySignup = authApi.verifySignup as jest.Mock;

const initialMetrics = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

describe('SignupScreen', () => {
  const mockEstablishSession = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    mockUseAuth.mockReturnValue({
      status: 'signedOut',
      user: null,
      establishSession: mockEstablishSession,
      updateUser: jest.fn(),
      signIn: jest.fn(),
      signOut: jest.fn(),
      refresh: jest.fn(),
    });
  });

  afterEach(async () => {
    await act(async () => {
      cleanup();
    });
  });

  const renderScreen = () =>
    render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <SignupScreen />
      </SafeAreaProvider>,
    );

  const fillForm = async (overrides: {
    name?: string;
    phone?: string;
    email?: string;
    password?: string;
    confirmPassword?: string;
    termsAccepted?: boolean;
  } = {}) => {
    const fields = {
      name: 'Alex Smith',
      phone: '519-555-0199',
      email: 'alex@example.com',
      password: 'StrongPass1!',
      confirmPassword: 'StrongPass1!',
      termsAccepted: true,
      ...overrides,
    };

    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Full name'), fields.name);
    });
    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Phone number'), fields.phone);
    });
    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Email address'), fields.email);
    });
    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Password'), fields.password);
    });
    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Confirm password'), fields.confirmPassword);
    });
    if (fields.termsAccepted) {
      await act(async () => {
        fireEvent.press(screen.getByLabelText('Terms and Privacy Policy agreement'));
      });
    }
  };

  it('renders Step 1 fields and switches to Sign In when requested', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByText('Create account')).toBeTruthy();
      expect(screen.getByLabelText('Full name')).toBeTruthy();
      expect(screen.getByLabelText('Phone number')).toBeTruthy();
      expect(screen.getByLabelText('Email address')).toBeTruthy();
      expect(screen.getByLabelText('Password')).toBeTruthy();
      expect(screen.getByLabelText('Confirm password')).toBeTruthy();
      expect(screen.getByLabelText('Terms and Privacy Policy agreement')).toBeTruthy();
      expect(screen.getByText('Continue')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Sign in'));
    });
    expect(router.push).toHaveBeenCalledWith('/auth/login');
  });

  it('validates terms and conditions acceptance is required', async () => {
    renderScreen();
    await waitFor(() => {
      expect(screen.getByLabelText('Full name')).toBeTruthy();
    });
    await fillForm({ termsAccepted: false });
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });
    await waitFor(() => {
      expect(screen.getByText('Please agree to the Terms of Service and acknowledge the Privacy Policy to continue.')).toBeTruthy();
    });
    expect(mockSendSignupOtp).not.toHaveBeenCalled();
  });

  it('validates required fields and password mismatch', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByText('Continue')).toBeTruthy();
    });

    // Empty name
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });
    await waitFor(() => {
      expect(screen.getByText('Full name is required.')).toBeTruthy();
    });

    // Invalid phone
    await fillForm({ name: 'Alex Smith', phone: '123' });
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });
    await waitFor(() => {
      expect(screen.getByText('Please enter a valid 10-digit phone number.')).toBeTruthy();
    });

    // Invalid email
    await fillForm({ phone: '5195550199', email: 'invalid-email' });
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });
    await waitFor(() => {
      expect(screen.getByText('Please enter a valid email address.')).toBeTruthy();
    });

    // Password mismatch
    await fillForm({
      email: 'alex@example.com',
      password: 'Password123!',
      confirmPassword: 'DifferentPassword123!',
    });
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });
    await waitFor(() => {
      expect(screen.getByText('Passwords do not match.')).toBeTruthy();
    });
  });

  it('submits valid Step 1 details and transitions to Step 2 OTP verification', async () => {
    mockSendSignupOtp.mockResolvedValueOnce({
      code: 'OTP_SENT',
      message: 'Verification code sent.',
      challengeId: 'chal-abc-123',
      attemptToken: 'attempt-xyz-789',
      nextResendAt: null,
      resendsRemaining: 3,
    });

    renderScreen();

    await waitFor(() => {
      expect(screen.getByLabelText('Full name')).toBeTruthy();
    });

    await fillForm();

    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });

    await waitFor(() => {
      expect(mockSendSignupOtp).toHaveBeenCalledWith({
        name: 'Alex Smith',
        phone: '5195550199',
        email: 'alex@example.com',
        password: 'StrongPass1!',
        idempotencyKey: 'signup-uuid-1234',
        attemptToken: null,
        termsAccepted: true,
        termsVersion: 'terms-v1.0',
        privacyVersion: 'privacy-v1.1',
      });
      expect(screen.getByText('Verify email')).toBeTruthy();
      expect(screen.getByLabelText('6-digit verification code')).toBeTruthy();
      expect(screen.getByText('Verify & create account')).toBeTruthy();
    });
  });

  it('verifies 6-digit OTP in Step 2 and establishes authenticated session', async () => {
    mockSendSignupOtp.mockResolvedValueOnce({
      code: 'OTP_SENT',
      message: 'Verification code sent.',
      challengeId: 'chal-abc-123',
      attemptToken: 'attempt-xyz-789',
      nextResendAt: null,
      resendsRemaining: 3,
    });

    const mockSessionUser: SessionUser = {
      token: 'jwt-new-user',
      name: 'Alex Smith',
      email: 'alex@example.com',
      phone: '5195550199',
      memberSince: 'November 2026',
      role: 'CUSTOMER',
      canUpdateProfile: true,
      nextProfileUpdateAt: null,
      noShowCount: 0,
      pickupDisabled: false,
    };
    mockVerifySignup.mockResolvedValueOnce(mockSessionUser);

    renderScreen();

    await waitFor(() => {
      expect(screen.getByLabelText('Full name')).toBeTruthy();
    });

    // Fill and submit Step 1
    await fillForm();
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });

    await waitFor(() => {
      expect(screen.getByLabelText('6-digit verification code')).toBeTruthy();
    });

    // Validate short OTP
    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('6-digit verification code'), '123');
    });
    await act(async () => {
      fireEvent.press(screen.getByText('Verify & create account'));
    });
    await waitFor(() => {
      expect(screen.getByText('Please enter the 6-digit verification code.')).toBeTruthy();
    });

    // Valid 6-digit OTP
    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('6-digit verification code'), '123456');
    });
    await act(async () => {
      fireEvent.press(screen.getByText('Verify & create account'));
    });

    await waitFor(() => {
      expect(mockVerifySignup).toHaveBeenCalledWith({
        name: 'Alex Smith',
        phone: '5195550199',
        email: 'alex@example.com',
        password: 'StrongPass1!',
        otp: '123456',
        challengeId: 'chal-abc-123',
        attemptToken: 'attempt-xyz-789',
        termsAccepted: true,
        termsVersion: 'terms-v1.0',
        privacyVersion: 'privacy-v1.1',
      });
      expect(mockEstablishSession).toHaveBeenCalledWith(mockSessionUser);
      expect(router.back).toHaveBeenCalled();
    });
  });

  it('displays API error during OTP verification failure', async () => {
    mockSendSignupOtp.mockResolvedValueOnce({
      code: 'OTP_SENT',
      message: 'Verification code sent.',
      challengeId: 'chal-abc-123',
      attemptToken: 'attempt-xyz-789',
      nextResendAt: null,
      resendsRemaining: 3,
    });

    mockVerifySignup.mockRejectedValueOnce(
      new ApiError({
        status: 400,
        code: 'OTP_INVALID',
        message: 'Invalid verification code. 2 attempts remaining.',
      }),
    );

    renderScreen();

    await waitFor(() => {
      expect(screen.getByLabelText('Full name')).toBeTruthy();
    });

    await fillForm();
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });

    await waitFor(() => {
      expect(screen.getByLabelText('6-digit verification code')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('6-digit verification code'), '999999');
    });
    await act(async () => {
      fireEvent.press(screen.getByText('Verify & create account'));
    });

    await waitFor(() => {
      expect(screen.getByText('Invalid verification code. 2 attempts remaining.')).toBeTruthy();
    });
    expect(mockEstablishSession).not.toHaveBeenCalled();
  });
});
