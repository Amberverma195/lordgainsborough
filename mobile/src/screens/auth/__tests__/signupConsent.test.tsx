import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as Linking from 'expo-linking';
import React from 'react';
import { Platform } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

jest.mock('expo-crypto', () => ({
  randomUUID: () => 'test-uuid-consent',
}));

jest.mock('expo-linking', () => ({
  openURL: jest.fn().mockResolvedValue(true),
  canOpenURL: jest.fn().mockResolvedValue(true),
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
  useAuth: jest.fn().mockReturnValue({
    status: 'signedOut',
    user: null,
    establishSession: jest.fn(),
    updateUser: jest.fn(),
    signIn: jest.fn(),
    signOut: jest.fn(),
    refresh: jest.fn(),
  }),
}));

import * as authApi from '../../../api/auth';
import SignupScreen, { getLegalDocumentUrl } from '../../../app/auth/signup';

const mockSendSignupOtp = authApi.sendSignupOtp as jest.Mock;

const initialMetrics = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

describe('SignupScreen Legal Consent & Document Access', () => {
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
        <SignupScreen />
      </SafeAreaProvider>,
    );

  it('renders accessible Terms of Service and Privacy Policy links', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByLabelText('Terms of Service')).toBeTruthy();
      expect(screen.getByLabelText('Privacy Policy')).toBeTruthy();
      expect(screen.getByLabelText('Terms and Privacy Policy agreement')).toBeTruthy();
    });
  });

  it('opens Terms of Service URL in secure browser view when tapped without clearing populated form', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByLabelText('Full name')).toBeTruthy();
    });

    // Populate the form fields
    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Full name'), 'Arthur Pendelton');
      fireEvent.changeText(screen.getByLabelText('Phone number'), '519-555-0123');
      fireEvent.changeText(screen.getByLabelText('Email address'), 'arthur@example.com');
      fireEvent.changeText(screen.getByLabelText('Password'), 'ValidPass123!');
      fireEvent.changeText(screen.getByLabelText('Confirm password'), 'ValidPass123!');
    });

    // Tap Terms of Service link
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Terms of Service'));
    });

    // Verifies Linking.openURL was called with the /terms endpoint
    expect(Linking.openURL).toHaveBeenCalledWith(expect.stringContaining('/terms'));

    // Verifies all unsent form fields remain intact
    expect(screen.getByDisplayValue('Arthur Pendelton')).toBeTruthy();
    expect(screen.getByDisplayValue('+1 (519)-555-0123')).toBeTruthy();
    expect(screen.getByDisplayValue('arthur@example.com')).toBeTruthy();
    expect(screen.getAllByDisplayValue('ValidPass123!')).toHaveLength(2);
  });

  it('opens Privacy Policy URL in secure browser view when tapped without clearing populated form', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByLabelText('Full name')).toBeTruthy();
    });

    // Populate the form fields
    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Full name'), 'Beatrice Vance');
      fireEvent.changeText(screen.getByLabelText('Email address'), 'beatrice@example.com');
    });

    // Tap Privacy Policy link
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Privacy Policy'));
    });

    // Verifies Linking.openURL was called with the /privacy endpoint
    expect(Linking.openURL).toHaveBeenCalledWith(expect.stringContaining('/privacy'));

    // Verifies all unsent form fields remain intact
    expect(screen.getByDisplayValue('Beatrice Vance')).toBeTruthy();
    expect(screen.getByDisplayValue('beatrice@example.com')).toBeTruthy();
  });

  it('mandates explicit legal consent and blocks submission when agreement is omitted', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByLabelText('Full name')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Full name'), 'Charles Xavier');
      fireEvent.changeText(screen.getByLabelText('Phone number'), '519-555-0144');
      fireEvent.changeText(screen.getByLabelText('Email address'), 'charles@example.com');
      fireEvent.changeText(screen.getByLabelText('Password'), 'Telepath1!');
      fireEvent.changeText(screen.getByLabelText('Confirm password'), 'Telepath1!');
    });

    // Submit WITHOUT checking the legal agreement
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });

    // Error is displayed and API is NOT invoked
    await waitFor(() => {
      expect(
        screen.getByText('Please agree to the Terms of Service and acknowledge the Privacy Policy to continue.'),
      ).toBeTruthy();
    });
    expect(mockSendSignupOtp).not.toHaveBeenCalled();

    // Now explicitly check agreement and re-submit
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Terms and Privacy Policy agreement'));
    });

    mockSendSignupOtp.mockResolvedValueOnce({
      code: 'OTP_SENT',
      message: 'Code sent',
      challengeId: 'chal-123',
      attemptToken: 'attempt-123',
      nextResendAt: null,
      resendsRemaining: 5,
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });

    await waitFor(() => {
      expect(mockSendSignupOtp).toHaveBeenCalledWith(
        expect.objectContaining({
          termsAccepted: true,
          termsVersion: 'terms-v1.0',
          privacyVersion: 'privacy-v1.1',
        }),
      );
    });
  });

  it('displays user-facing error message when Terms of Service fails to open and preserves form inputs', async () => {
    (Linking.openURL as jest.Mock).mockRejectedValueOnce(new Error('Browser not found'));
    renderScreen();

    await waitFor(() => {
      expect(screen.getByLabelText('Full name')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Full name'), 'Arthur Pendelton');
      fireEvent.changeText(screen.getByLabelText('Email address'), 'arthur@example.com');
      fireEvent.changeText(screen.getByLabelText('Password'), 'ValidPass123!');
    });

    // Tap Terms of Service
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Terms of Service'));
    });

    await waitFor(() => {
      expect(
        screen.getByText('Unable to open Terms of Service. Please check your browser or network settings.'),
      ).toBeTruthy();
    });

    // All form values remain intact
    expect(screen.getByDisplayValue('Arthur Pendelton')).toBeTruthy();
    expect(screen.getByDisplayValue('arthur@example.com')).toBeTruthy();
    expect(screen.getByDisplayValue('ValidPass123!')).toBeTruthy();
  });

  it('displays user-facing error message when Privacy Policy cannot be opened by device and preserves form inputs', async () => {
    (Linking.canOpenURL as jest.Mock).mockResolvedValueOnce(false);
    renderScreen();

    await waitFor(() => {
      expect(screen.getByLabelText('Full name')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(screen.getByLabelText('Full name'), 'Beatrice Vance');
      fireEvent.changeText(screen.getByLabelText('Email address'), 'beatrice@example.com');
    });

    // Tap Privacy Policy
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Privacy Policy'));
    });

    await waitFor(() => {
      expect(
        screen.getByText('Unable to open Privacy Policy. Please check your browser or network settings.'),
      ).toBeTruthy();
    });

    // Form inputs remain intact
    expect(screen.getByDisplayValue('Beatrice Vance')).toBeTruthy();
    expect(screen.getByDisplayValue('beatrice@example.com')).toBeTruthy();
  });

  it('resolves reachable production Vercel legal URLs and adapts loopback on Android', () => {
    const originalEnv = { ...process.env };
    try {
      delete process.env.EXPO_PUBLIC_LEGAL_URL;
      delete process.env.EXPO_PUBLIC_FRONTEND_URL;

      expect(getLegalDocumentUrl('/terms')).toBe('https://lordgainsborough.vercel.app/terms');
      expect(getLegalDocumentUrl('/privacy')).toBe('https://lordgainsborough.vercel.app/privacy');

      // Custom override
      process.env.EXPO_PUBLIC_LEGAL_URL = 'https://custom.example.com';
      expect(getLegalDocumentUrl('/terms')).toBe('https://custom.example.com/terms');

      // Android emulator loopback adaptation
      const originalPlatform = Platform.OS;
      try {
        (Platform as any).OS = 'android';
        delete process.env.EXPO_PUBLIC_LEGAL_URL;
        process.env.EXPO_PUBLIC_FRONTEND_URL = 'http://localhost:3000';
        expect(getLegalDocumentUrl('/terms')).toBe('http://10.0.2.2:3000/terms');
      } finally {
        (Platform as any).OS = originalPlatform;
      }
    } finally {
      process.env = originalEnv;
    }
  });
});
