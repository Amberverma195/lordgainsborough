import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { router } from 'expo-router';
import React from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

jest.mock('expo-router', () => ({
  router: {
    push: jest.fn(),
    replace: jest.fn(),
    back: jest.fn(),
    canGoBack: jest.fn().mockReturnValue(true),
  },
}));

jest.mock('../../../api/auth', () => ({
  updateProfile: jest.fn(),
}));

jest.mock('../../../auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

import * as authApi from '../../../api/auth';
import { ApiError } from '../../../api/errors';
import { SessionUser } from '../../../api/parse';
import { useAuth } from '../../../auth/AuthContext';
import ProfileEditScreen from '../edit';

const mockUseAuth = useAuth as jest.Mock;
const mockUpdateProfile = authApi.updateProfile as jest.Mock;

const initialMetrics = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

describe('ProfileEditScreen', () => {
  const mockUser: SessionUser = {
    token: 'jwt-123',
    name: 'Jane Doe',
    email: 'jane@example.com',
    phone: '5195550199',
    memberSince: 'January 2026',
    role: 'CUSTOMER',
    canUpdateProfile: true,
    nextProfileUpdateAt: null,
    noShowCount: 0,
    pickupDisabled: false,
  };

  const mockUpdateUser = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    mockUseAuth.mockReturnValue({
      status: 'signedIn',
      user: mockUser,
      updateUser: mockUpdateUser,
      establishSession: jest.fn(),
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
        <ProfileEditScreen />
      </SafeAreaProvider>,
    );

  it('renders customer profile information with editable name and read-only email/phone', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByDisplayValue('Jane Doe')).toBeTruthy();
      expect(screen.getByText('jane@example.com')).toBeTruthy();
      expect(screen.getByText('5195550199')).toBeTruthy();
      expect(screen.getByText('Email is tied to your account and cannot be modified.')).toBeTruthy();
    });
  });

  it('validates against empty name', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByDisplayValue('Jane Doe')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(screen.getByDisplayValue('Jane Doe'), '   ');
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Save changes'));
    });

    await waitFor(() => {
      expect(screen.getByText('Please enter your full name.')).toBeTruthy();
    });
    expect(mockUpdateProfile).not.toHaveBeenCalled();
  });

  it('saves updated name, calls updateUser, and navigates back', async () => {
    const updatedUser: SessionUser = {
      ...mockUser,
      name: 'Jane Smith',
      token: 'jwt-updated',
    };
    mockUpdateProfile.mockResolvedValueOnce(updatedUser);

    renderScreen();

    await waitFor(() => {
      expect(screen.getByDisplayValue('Jane Doe')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(screen.getByDisplayValue('Jane Doe'), 'Jane Smith');
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Save changes'));
    });

    await waitFor(() => {
      expect(mockUpdateProfile).toHaveBeenCalledWith('jwt-123', 'Jane Smith');
      expect(mockUpdateUser).toHaveBeenCalledWith(updatedUser);
      expect(router.back).toHaveBeenCalled();
    });
  });

  it('displays API error if updateProfile fails', async () => {
    mockUpdateProfile.mockRejectedValueOnce(
      new ApiError({
        status: 429,
        code: 'PROFILE_UPDATE_RATE_LIMITED',
        message: 'You can only update your profile name once per week.',
      }),
    );

    renderScreen();

    await waitFor(() => {
      expect(screen.getByDisplayValue('Jane Doe')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.changeText(screen.getByDisplayValue('Jane Doe'), 'Jane Smith');
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Save changes'));
    });

    await waitFor(() => {
      expect(screen.getByText('You can only update your profile name once per week.')).toBeTruthy();
    });
    expect(mockUpdateUser).not.toHaveBeenCalled();
    expect(router.back).not.toHaveBeenCalled();
  });

  it('navigates back when Cancel is pressed', async () => {
    renderScreen();

    await waitFor(() => {
      expect(screen.getByText('Cancel')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Cancel'));
    });
    expect(router.back).toHaveBeenCalled();
  });

  it('renders sign-in prompt when signed out', async () => {
    mockUseAuth.mockReturnValue({
      status: 'signedOut',
      user: null,
      updateUser: mockUpdateUser,
      establishSession: jest.fn(),
      signIn: jest.fn(),
      signOut: jest.fn(),
      refresh: jest.fn(),
    });

    renderScreen();

    await waitFor(() => {
      expect(screen.getByText('Sign in to edit your profile.')).toBeTruthy();
    });

    await act(async () => {
      fireEvent.press(screen.getByText('Sign in'));
    });
    expect(router.push).toHaveBeenCalledWith('/auth/login');
  });
});
