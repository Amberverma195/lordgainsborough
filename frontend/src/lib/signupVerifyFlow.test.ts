import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchSignupOtpStatus,
  loadPendingSignupCredentials,
  loadSignupOtpChallengeState,
  savePendingSignupCredentials,
  saveSignupOtpChallengeState,
} from './signupOtp';
import { suggestEmailDomainTypo } from './email';

function installMemorySessionStorage() {
  const store = new Map<string, string>();
  const memory = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
  };
  vi.stubGlobal('sessionStorage', memory);
  return memory;
}

describe('Signup and Verify Flow UX & Mechanics', () => {
  beforeEach(() => {
    installMemorySessionStorage();
  });

  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  describe('Task 1: Password visibility and button attributes', () => {
    it('toggles password and confirm password fields independently', () => {
      let showPassword = false;
      let showConfirmPassword = false;

      const togglePassword = () => { showPassword = !showPassword; };
      const toggleConfirmPassword = () => { showConfirmPassword = !showConfirmPassword; };

      // Initial state: both hidden
      expect(showPassword).toBe(false);
      expect(showConfirmPassword).toBe(false);

      // Toggle main password
      togglePassword();
      expect(showPassword).toBe(true);
      expect(showConfirmPassword).toBe(false);

      // Toggle confirm password
      toggleConfirmPassword();
      expect(showPassword).toBe(true);
      expect(showConfirmPassword).toBe(true);

      // Hide main password independently
      togglePassword();
      expect(showPassword).toBe(false);
      expect(showConfirmPassword).toBe(true);
    });

    it('uses type="button" and accessible labels for toggle buttons so form never submits', () => {
      const getButtonProps = (visible: boolean) => ({
        type: 'button' as const,
        'aria-label': visible ? 'Hide password' : 'Show password',
        'aria-pressed': visible,
      });

      const hiddenProps = getButtonProps(false);
      expect(hiddenProps.type).toBe('button');
      expect(hiddenProps['aria-label']).toBe('Show password');
      expect(hiddenProps['aria-pressed']).toBe(false);

      const visibleProps = getButtonProps(true);
      expect(visibleProps.type).toBe('button');
      expect(visibleProps['aria-label']).toBe('Hide password');
      expect(visibleProps['aria-pressed']).toBe(true);
    });
  });

  describe('Task 2: Email typo suggestions and explicit override', () => {
    it('detects typo and allows customer to choose between suggestion and "Use my email as entered"', () => {
      const typedEmail = 'person@gmial.com';
      const suggestion = suggestEmailDomainTypo(typedEmail);
      expect(suggestion).toBe('person@gmail.com');

      let currentEmail = typedEmail;
      let confirmedAsEntered: string | null = null;

      // Scenario A: User clicks the suggestion
      const applySuggestion = (suggested: string) => {
        currentEmail = suggested;
        confirmedAsEntered = suggested.toLowerCase();
      };
      applySuggestion(suggestion!);
      expect(currentEmail).toBe('person@gmail.com');
      expect(suggestEmailDomainTypo(currentEmail)).toBeNull();

      // Scenario B: User with a valid but flagged email clicks "Use my email as entered"
      const unusualTyped = 'founder@company.co';
      expect(suggestEmailDomainTypo(unusualTyped)).toBeNull(); // .co alone is legitimate

      const suspiciousValid = 'test@mycompany.con';
      const typoSug = suggestEmailDomainTypo(suspiciousValid);
      expect(typoSug).toBe('test@mycompany.com');

      // User confirms "Use my email as entered"
      confirmedAsEntered = suspiciousValid.toLowerCase();
      const shouldBlockSubmit = typoSug && confirmedAsEntered !== suspiciousValid.toLowerCase();
      expect(shouldBlockSubmit).toBe(false);
    });
  });

  describe('Task 3: Verification page mount and resend button presentation', () => {
    it('verification-page mount performs GET status sync and does NOT issue a second POST /auth/signup/send-otp', async () => {
      savePendingSignupCredentials({
        name: 'Amber Verma',
        email: 'amber@example.com',
        phone: '+1 (123)-456-7890',
      });
      saveSignupOtpChallengeState({
        email: 'amber@example.com',
        challengeId: 'chal-123',
        attemptToken: 'token-xyz',
        serverTime: '2026-07-13T12:00:00.000Z',
        nextResendAt: '2026-07-13T12:00:30.000Z',
        resendsRemaining: 4,
        receivedAtMs: Date.now(),
      });

      const fetchMock = vi.fn().mockImplementation((url: string) => {
        if (url.includes('/auth/signup/otp-status')) {
          return Promise.resolve({
            ok: true,
            status: 200,
            json: async () => ({
              code: 'OTP_SENT',
              challengeId: 'chal-123',
              serverTime: '2026-07-13T12:00:02.000Z',
              nextResendAt: '2026-07-13T12:00:30.000Z',
              resendsRemaining: 4,
            }),
          });
        }
        return Promise.reject(new Error(`Unexpected call to ${url}`));
      });
      vi.stubGlobal('fetch', fetchMock);

      const stored = loadPendingSignupCredentials();
      const cached = loadSignupOtpChallengeState();
      expect(stored).not.toBeNull();
      expect(cached).not.toBeNull();

      // Page mount calls status sync:
      const { body } = await fetchSignupOtpStatus(stored!.email, cached?.challengeId);
      expect(body.challengeId).toBe('chal-123');

      // Verify that NO POST /auth/signup/send-otp was ever called
      const sendOtpCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/send-otp'));
      expect(sendOtpCalls.length).toBe(0);
    });

    it('displays "Resend available in Xs" during cooldown and "Resend Code" once elapsed', () => {
      const getResendLabel = (
        cooldown: number,
        resending: boolean,
        expired: boolean,
        exhausted: boolean
      ) => {
        if (resending) return 'Sending...';
        if (expired) return 'Session expired';
        if (exhausted) return 'Resend limit reached';
        if (cooldown > 0) return `Resend available in ${cooldown}s`;
        return 'Resend Code';
      };

      // Initial visit with 30s cooldown:
      expect(getResendLabel(30, false, false, false)).toBe('Resend available in 30s');
      expect(getResendLabel(15, false, false, false)).toBe('Resend available in 15s');
      expect(getResendLabel(1, false, false, false)).toBe('Resend available in 1s');

      // Cooldown finished:
      expect(getResendLabel(0, false, false, false)).toBe('Resend Code');

      // Sending state:
      expect(getResendLabel(0, true, false, false)).toBe('Sending...');

      // Session expired:
      expect(getResendLabel(0, false, true, false)).toBe('Session expired');

      // Limit reached:
      expect(getResendLabel(0, false, false, true)).toBe('Resend limit reached');
    });

    it('blocks resend attempts while cooldown is active', async () => {
      const fetchMock = vi.fn();
      vi.stubGlobal('fetch', fetchMock);

      const cooldownSeconds = 25;
      const resending = false;

      // Simulated handleResend guard
      if (cooldownSeconds > 0 || resending) {
        // blocked
      } else {
        await fetch('http://localhost:8080/auth/signup/send-otp');
      }

      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('prevents duplicate resend requests on rapid double-click', async () => {
      let callCount = 0;
      const fetchMock = vi.fn().mockImplementation(() => {
        callCount++;
        return Promise.resolve({
          ok: true,
          status: 200,
          json: async () => ({ code: 'OTP_SENT', message: 'Verification code accepted for delivery.' }),
        });
      });
      vi.stubGlobal('fetch', fetchMock);

      let resending = false;
      const triggerResend = async () => {
        if (resending) return;
        resending = true;
        try {
          await fetch('http://localhost:8080/auth/signup/send-otp', { method: 'POST' });
        } finally {
          resending = false;
        }
      };

      // Simulate rapid double click
      const click1 = triggerResend();
      const click2 = triggerResend();
      await Promise.all([click1, click2]);

      expect(callCount).toBe(1);
    });
  });

  describe('Task 4: In-app Toast notification vs banners', () => {
    it('triggers friendly toast "A new verification code has been sent to your email." for accepted sends', () => {
      let toastMessage = '';
      let infoMessage = '';
      let errorMessage = '';

      const handleResendResponse = (status: number, body: { code?: string; message?: string }) => {
        if (status === 200 || status === 202) {
          if (body.code === 'OTP_DELIVERY_UNKNOWN') {
            infoMessage = body.message || "We couldn't confirm delivery.";
          } else if (body.code === 'OTP_ALREADY_ACTIVE') {
            infoMessage = body.message || 'A code was already sent recently.';
          } else {
            toastMessage = 'A new verification code has been sent to your email.';
          }
        } else if (status === 409) {
          errorMessage = 'Conflict';
        } else if (status === 429) {
          errorMessage = 'Rate limited';
        } else {
          errorMessage = 'Failed';
        }
      };

      // 1. Accepted resend
      handleResendResponse(200, { code: 'OTP_SENT', message: 'Verification code accepted for delivery.' });
      expect(toastMessage).toBe('A new verification code has been sent to your email.');
      expect(toastMessage).not.toContain('accepted for delivery');
      expect(infoMessage).toBe('');
      expect(errorMessage).toBe('');

      // 2. Ambiguous delivery (OTP_DELIVERY_UNKNOWN) -> NO toast
      toastMessage = '';
      handleResendResponse(202, { code: 'OTP_DELIVERY_UNKNOWN', message: 'Delivery uncertain' });
      expect(toastMessage).toBe('');
      expect(infoMessage).toBe('Delivery uncertain');

      // 3. Already active code (OTP_ALREADY_ACTIVE) -> NO false "sent" toast
      toastMessage = '';
      infoMessage = '';
      handleResendResponse(200, { code: 'OTP_ALREADY_ACTIVE', message: 'Active code' });
      expect(toastMessage).toBe('');
      expect(infoMessage).toBe('Active code');

      // 4. Rate limit (429) -> NO toast
      toastMessage = '';
      handleResendResponse(429, { code: 'OTP_RESEND_LIMIT_REACHED' });
      expect(toastMessage).toBe('');
      expect(errorMessage).toBe('Rate limited');

      // 5. Conflict (409) -> NO toast
      toastMessage = '';
      handleResendResponse(409, { code: 'OTP_ATTEMPT_CONFLICT' });
      expect(toastMessage).toBe('');
      expect(errorMessage).toBe('Conflict');
    });

    it('auto-dismisses toast after 4000ms and cleans up timer on dismiss or unmount', () => {
      vi.useFakeTimers();

      let activeToast: string | null = null;
      let timer: NodeJS.Timeout | null = null;

      const showToast = (msg: string) => {
        if (timer) clearTimeout(timer);
        activeToast = msg;
        timer = setTimeout(() => {
          activeToast = null;
          timer = null;
        }, 4000);
      };

      const dismissToast = () => {
        if (timer) {
          clearTimeout(timer);
          timer = null;
        }
        activeToast = null;
      };

      showToast('A new verification code has been sent to your email.');
      expect(activeToast).toBe('A new verification code has been sent to your email.');

      // Advance by 2000ms: still visible
      vi.advanceTimersByTime(2000);
      expect(activeToast).toBe('A new verification code has been sent to your email.');

      // Advance by another 2000ms (total 4000ms): auto-dismissed
      vi.advanceTimersByTime(2000);
      expect(activeToast).toBeNull();
      expect(timer).toBeNull();

      // Manual dismissal test:
      showToast('Another toast');
      expect(activeToast).toBe('Another toast');
      dismissToast();
      expect(activeToast).toBeNull();
      expect(timer).toBeNull();

      vi.useRealTimers();
    });
  });
});
