import { test, expect } from '@playwright/test';
import { Client } from 'pg';
import { hashSync } from 'bcryptjs';
import { randomUUID, randomInt } from 'node:crypto';
import { readFile } from 'node:fs/promises';

async function getE2eDb() {
  const state = JSON.parse(await readFile('.e2e/state.json', 'utf8'));
  if (!/^lgb_e2e_[a-f0-9]{32}$/.test(state.database)) throw Error('Refusing non-E2E database');
  const db = new Client({
    host: '127.0.0.1',
    port: state.port,
    database: state.database,
    user: 'postgres',
    password: 'postgres',
  });
  await db.connect();
  return { db, state };
}

async function getLatestInboxOtp(toAddress: string, offset = 0): Promise<string> {
  let code = '';
  await expect.poll(async () => {
    const messages = await (await fetch('http://127.0.0.1:8025/messages')).json();
    const match = messages.slice(offset).findLast((m: { to: string[] }) => m.to.includes(toAddress));
    code = match?.code || '';
    return code;
  }).toMatch(/^\d{6}$/);
  return code;
}

test.describe('Phase 8: Unified Login & Recovery Regression Tests', () => {
  test.beforeEach(async () => {
    const { db } = await getE2eDb();
    try {
      await db.query('TRUNCATE signup_abuse_limits, login_failure_states');
    } finally {
      await db.end();
    }
  });

  test('01: Two-step Login with email, phone, and change-identifier', async ({ page }) => {
    const { db } = await getE2eDb();
    const email = `two-step-${randomUUID()}@example.test`;
    const phone = `+1 (519)-${randomInt(200, 999)}-${randomInt(1000, 9999)}`;
    const phoneDigits = phone.replace(/\D/g, '').slice(-10);
    const password = 'ValidPassword1!';
    const userId = randomUUID();

    try {
      await db.query(
        'INSERT INTO users(id, name, email, phone, phone_normalized, password, role) VALUES($1, $2, $3, $4, $5, $6, $7)',
        [userId, 'Unified Member', email, phone, phoneDigits, hashSync(password, 10), 'MEMBER']
      );

      await page.goto('/login');

      // 1. Invalid identifier syntax rejected locally
      await page.getByPlaceholder('Email or phone number').fill('invalid-format');
      await page.getByRole('button', { name: 'Continue' }).click();
      await expect(page.getByText(/valid/i)).toBeVisible();

      // 2. Valid email -> Continue shows password step
      await page.getByPlaceholder('Email or phone number').fill(email);
      await page.getByRole('button', { name: 'Continue' }).click();
      await expect(page.getByText(email)).toBeVisible();
      await expect(page.locator('input[type=password]')).toBeVisible();

      // 3. "Use a different email/phone" returns to step 1
      await page.getByRole('button', { name: /Use a different email\/phone/i }).click();
      await expect(page.getByPlaceholder('Email or phone number')).toBeVisible();

      // 4. Test phone identifier
      await page.getByPlaceholder('Email or phone number').fill(phone);
      await page.getByRole('button', { name: 'Continue' }).click();
      await expect(page.locator('input[type=password]')).toBeVisible();

      // 5. Wrong password shows generic error
      await page.locator('input[type=password]').fill('WrongPassword99!');
      await page.getByRole('button', { name: 'Sign In' }).click();
      await expect(page.getByText('The email, phone number, or password is incorrect.')).toBeVisible();

      // 6. Correct password logs in and redirects to profile
      await page.locator('input[type=password]').fill(password);
      await page.getByRole('button', { name: 'Sign In' }).click();
      await expect(page).toHaveURL(/\/profile$/);
      await expect(page.getByRole('heading', { name: 'Unified Member' })).toBeVisible();
    } finally {
      await db.end();
    }
  });

  test('02: Password recovery via email, OTP verification, and token version increment', async ({ page }) => {
    const { db } = await getE2eDb();
    const email = `recovery-email-${randomUUID()}@example.test`;
    const oldPassword = 'OldPassword1!';
    const newPassword = 'NewPassword99!';
    const userId = randomUUID();

    try {
      await db.query(
        'INSERT INTO users(id, name, email, password, role, token_version) VALUES($1, $2, $3, $4, $5, 0)',
        [userId, 'Recovery Member', email, hashSync(oldPassword, 10), 'MEMBER']
      );

      const offset = (await (await fetch('http://127.0.0.1:8025/messages')).json()).length;

      // Start from /login -> click "Forgot password?" from password step
      await page.goto('/login');
      await page.getByPlaceholder('Email or phone number').fill(email);
      await page.getByRole('button', { name: 'Continue' }).click();
      await page.getByRole('button', { name: 'Forgot password?' }).click();

      // Ensure we are still under /login URL
      await expect(page).toHaveURL(/\/login$/);
      await expect(page.getByRole('heading', { name: 'Reset Password' })).toBeVisible();

      // Identifier is pre-filled from login step
      await expect(page.getByPlaceholder(/email.*phone/i)).toHaveValue(email);
      await page.getByRole('button', { name: 'Send Reset Code' }).click();

      // Enters OTP verification step
      await expect(page.getByRole('button', { name: 'Reset Password' })).toBeVisible();

      // Fetch OTP from loopback inbox
      const otpCode = await getLatestInboxOtp(email, offset);
      await page.getByPlaceholder('6-digit code').fill(otpCode);
      await page.getByPlaceholder('Enter new password', { exact: true }).fill(newPassword);
      await page.getByPlaceholder('Re-enter new password', { exact: true }).fill(newPassword);
      await page.getByRole('button', { name: 'Reset Password' }).click();

      // Shows Password Updated screen
      await expect(page.getByRole('heading', { name: 'Password Updated' })).toBeVisible();

      // Database verifies token_version was incremented to 1
      const tokenVersion = (await db.query('SELECT token_version FROM users WHERE id = $1', [userId])).rows[0].token_version;
      expect(Number(tokenVersion)).toBe(1);

      // Click "Sign In" to return to login and authenticate with the new password
      await page.getByRole('button', { name: 'Sign In' }).click();
      await expect(page.getByPlaceholder('Email or phone number')).toBeVisible();
      await page.getByPlaceholder('Email or phone number').fill(email);
      await page.getByRole('button', { name: 'Continue' }).click();
      await page.locator('input[type=password]').fill(newPassword);
      await page.getByRole('button', { name: 'Sign In' }).click();
      await expect(page).toHaveURL(/\/profile$/);
    } finally {
      await db.end();
    }
  });

  test('03: Password recovery via phone delivers OTP to associated email', async ({ page }) => {
    const { db } = await getE2eDb();
    const email = `phone-recovery-${randomUUID()}@example.test`;
    const phone = `+1 (519)-${randomInt(200, 999)}-${randomInt(1000, 9999)}`;
    const phoneDigits = phone.replace(/\D/g, '').slice(-10);
    const oldPassword = 'OldPassword1!';
    const newPassword = 'NewPassword88!';
    const userId = randomUUID();

    try {
      await db.query(
        'INSERT INTO users(id, name, email, phone, phone_normalized, password, role, token_version) VALUES($1, $2, $3, $4, $5, $6, $7, 0)',
        [userId, 'Phone Recovery User', email, phone, phoneDigits, hashSync(oldPassword, 10), 'MEMBER']
      );

      const offset = (await (await fetch('http://127.0.0.1:8025/messages')).json()).length;

      await page.goto('/login');
      await page.getByPlaceholder('Email or phone number').fill(phone);
      await page.getByRole('button', { name: 'Continue' }).click();
      await page.getByRole('button', { name: 'Forgot password?' }).click();

      // Submit reset code using the phone number
      await page.getByRole('button', { name: 'Send Reset Code' }).click();

      // Enters verification step
      await expect(page.getByRole('button', { name: 'Reset Password' })).toBeVisible();

      // OTP was delivered to user's registered email
      const otpCode = await getLatestInboxOtp(email, offset);
      await page.getByPlaceholder('6-digit code').fill(otpCode);
      await page.getByPlaceholder('Enter new password', { exact: true }).fill(newPassword);
      await page.getByPlaceholder('Re-enter new password', { exact: true }).fill(newPassword);
      await page.getByRole('button', { name: 'Reset Password' }).click();

      await expect(page.getByRole('heading', { name: 'Password Updated' })).toBeVisible();
    } finally {
      await db.end();
    }
  });

  test('04: Unknown email and unknown phone issue shadow challenges without enumeration', async ({ page }) => {
    const unknownEmail = `unknown-${randomUUID()}@example.test`;
    const unknownPhone = `+1 (519)-999-${randomInt(1000, 9999)}`;

    await page.goto('/login?intent=recover');
    await expect(page.getByRole('heading', { name: 'Reset Password' })).toBeVisible();

    // Unknown email
    await page.getByPlaceholder(/email.*phone/i).fill(unknownEmail);
    await page.getByRole('button', { name: 'Send Reset Code' }).click();

    // UI transitions identically to verification step (no enumeration leak)
    await expect(page.getByRole('button', { name: 'Reset Password' })).toBeVisible();
    await expect(page.getByText('If an account matches that email or phone, a reset code has been sent.')).toBeVisible();

    // Start a fresh recovery request for the unknown phone
    await page.goto('/login?intent=recover');
    await expect(page.getByPlaceholder(/email.*phone/i)).toBeVisible();

    // Unknown phone
    await page.getByPlaceholder(/email.*phone/i).fill(unknownPhone);
    await page.getByRole('button', { name: 'Send Reset Code' }).click();
    await expect(page.getByRole('button', { name: 'Reset Password' })).toBeVisible();
    await expect(page.getByText('If an account matches that email or phone, a reset code has been sent.')).toBeVisible();
  });

  test('06: Browser navigation: Back from recovery returns to clean login', async ({ page }) => {
    await page.goto('/login');
    await page.getByPlaceholder('Email or phone number').fill('test@example.test');
    await page.getByRole('button', { name: 'Continue' }).click();

    // Go to forgot password
    await page.getByRole('button', { name: 'Forgot password?' }).click();
    await expect(page.getByRole('heading', { name: 'Reset Password' })).toBeVisible();

    // Browser Back should return to login identifier step
    await page.goBack();
    await expect(page.getByPlaceholder('Email or phone number')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Continue' })).toBeVisible();

    // Repeated cycles of Forgot Password -> Back to Sign In do not accumulate history
    for (let i = 0; i < 3; i++) {
      await page.getByPlaceholder('Email or phone number').fill('test@example.test');
      await page.getByRole('button', { name: 'Continue' }).click();
      await page.getByRole('button', { name: 'Forgot password?' }).click();
      await expect(page.getByRole('heading', { name: 'Reset Password' })).toBeVisible();
      await page.getByRole('button', { name: /Back to Sign In/i }).click();
      await expect(page.getByPlaceholder('Email or phone number')).toBeVisible();
    }

    // Direct access to /forgot-password route redirects to /login with recovery intent
    await page.goto('/forgot-password');
    await expect(page).toHaveURL(/\/login(\?intent=recover)?$/);
    await expect(page.getByRole('heading', { name: 'Reset Password' })).toBeVisible();
  });

  test('07: Browser Back during in-flight OTP request discards response and remains on login', async ({ page }) => {
    await page.goto('/login');
    await page.getByPlaceholder('Email or phone number').fill('inflight-test@example.test');
    await page.getByRole('button', { name: 'Continue' }).click();
    await page.getByRole('button', { name: 'Forgot password?' }).click();

    // Delay send-otp response to test in-flight race condition
    await page.route('**/auth/forgot-password/send-otp', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'RESET_CODE_SENT',
          challengeId: 'c-delayed-test',
          serverTime: new Date().toISOString(),
        }),
      });
    });

    // Click Send Reset Code
    await page.getByRole('button', { name: 'Send Reset Code' }).click();

    // Immediately press browser Back while request is in flight
    await page.goBack();

    // Verify user is back on login identifier step
    await expect(page.getByPlaceholder('Email or phone number')).toBeVisible();

    // Wait 1.2s for delayed response to arrive
    await page.waitForTimeout(1200);

    // Verify screen REMAINS on login identifier and did NOT jump to reset-verify
    await expect(page.getByPlaceholder('Email or phone number')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Continue' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Reset Password' })).toHaveCount(0);
  });

  test('08: Consolidated Signup: single-route registration and inline OTP verification', async ({ page }) => {
    const { db } = await getE2eDb();
    const email = `consolidated-signup-${randomUUID()}@example.test`;
    const phone = `+1 (519)-${randomInt(200, 999)}-${randomInt(1000, 9999)}`;
    const password = 'SignupPass123!';
    const name = 'Consolidated Member';

    try {
      const offset = (await (await fetch('http://127.0.0.1:8025/messages')).json()).length;

      await page.goto('/signup');

      // 1. Submit form
      await page.getByPlaceholder('Enter your full name').fill(name);
      await page.getByPlaceholder('+1 (123)-456-7890').fill(phone);
      await page.getByPlaceholder('Enter email address').fill(email);
      await page.getByPlaceholder('Enter Password', { exact: true }).fill(password);
      await page.getByPlaceholder('Re-enter password', { exact: true }).fill(password);
      await page.getByRole('checkbox').check();
      await page.getByRole('button', { name: 'Create Account' }).click();

      // 2. Inline OTP verification appears without route navigation away from /signup
      await expect(page).toHaveURL(/\/signup$/);
      await expect(page.getByRole('heading', { name: 'Verify Your Email' })).toBeVisible();
      await expect(page.getByRole('button', { name: /Change Email Address/i })).toBeVisible();

      // 3. Test "Change Email Address" preserves other field values
      await page.getByRole('button', { name: /Change Email Address/i }).click();
      await expect(page.getByRole('heading', { name: 'Join the Club' })).toBeVisible();
      await expect(page.getByPlaceholder('Enter your full name')).toHaveValue(name);
      await expect(page.getByPlaceholder('+1 (123)-456-7890')).toHaveValue(phone);

      // 4. Update to the corrected email address, re-submit and verify
      const correctedEmail = `consolidated-corrected-${randomUUID()}@example.test`;
      await page.getByPlaceholder('Enter email address').fill(correctedEmail);
      await page.getByPlaceholder('Enter Password', { exact: true }).fill(password);
      await page.getByPlaceholder('Re-enter password', { exact: true }).fill(password);
      await page.getByRole('checkbox').check();
      await page.getByRole('button', { name: 'Create Account' }).click();
      await expect(page.getByRole('heading', { name: 'Verify Your Email' })).toBeVisible();

      const otp = await getLatestInboxOtp(correctedEmail, offset);
      for (let i = 0; i < 6; i++) {
        await page.getByRole('textbox', { name: `Digit ${i + 1}` }).fill(otp[i]);
      }
      await page.getByRole('button', { name: /Verify Email & Create Account/i }).click();

      // 5. Successfully authenticated and forwarded to profile
      await expect(page).toHaveURL(/\/profile$/);
      await expect(page.getByRole('heading', { name: 'Consolidated Member' })).toBeVisible();

      // Legacy /signup/verify route redirects cleanly to /signup
      await page.goto('/signup/verify');
      await expect(page).toHaveURL(/\/signup$/);
    } finally {
      await db.end();
    }
  });

  test('09: Password reset expiry, Start New Reset action, cooldown, history hygiene, and fresh OTP recovery', async ({ page }) => {
    const { db } = await getE2eDb();
    const email = `expiry-restart-${randomUUID()}@example.test`;
    const oldPassword = 'OldPassword1!';
    const newPassword = 'NewPassword100!';
    const userId = randomUUID();

    try {
      await db.query(
        'INSERT INTO users(id, name, email, password, role, token_version) VALUES($1, $2, $3, $4, $5, 0)',
        [userId, 'Expiry Member', email, hashSync(oldPassword, 10), 'MEMBER']
      );

      const offset = (await (await fetch('http://127.0.0.1:8025/messages')).json()).length;

      // 1. Route send-otp to return a challenge that expires in 2 seconds
      await page.route('**/auth/forgot-password/send-otp', async (route) => {
        const response = await route.fetch();
        const json = await response.json();
        json.challengeExpiresAt = new Date(Date.now() + 2000).toISOString();
        json.nextResendAt = new Date(Date.now() + 1000).toISOString();
        await route.fulfill({
          response,
          json,
        });
      });

      await page.goto('/login');
      await page.getByPlaceholder('Email or phone number').fill(email);
      await page.getByRole('button', { name: 'Continue' }).click();
      await page.getByRole('button', { name: 'Forgot password?' }).click();
      await page.getByRole('button', { name: 'Send Reset Code' }).click();

      // Verify we are on reset-verify step
      await expect(page.getByRole('button', { name: 'Reset Password' })).toBeVisible();

      // Fill in dummy OTP and password fields to test clearing
      await page.getByPlaceholder('6-digit code').fill('123456');
      await page.getByPlaceholder('Enter new password', { exact: true }).fill(newPassword);
      await page.getByPlaceholder('Re-enter new password', { exact: true }).fill(newPassword);

      // 2. Wait 2.5s for challenge to expire
      await page.waitForTimeout(2500);
      // Synchronize database to reflect challenge expiry so backend atomically replaces it on fresh request
      await db.query(
        "UPDATE password_reset_otp_challenges SET challenge_expires_at = NOW() - INTERVAL '5 seconds', next_resend_at = NOW() - INTERVAL '5 seconds' WHERE user_id = $1",
        [userId]
      );

      // Verify "Start New Reset" button appears
      const startNewResetBtn = page.getByRole('button', { name: 'Start New Reset' }).first();
      await expect(startNewResetBtn).toBeVisible();
      await expect(page.getByRole('button', { name: 'Reset Password' })).toHaveCount(0);

      // 3. Click "Start New Reset"
      await startNewResetBtn.click();

      // Verify returned to reset-request (identifier step)
      await expect(page.getByRole('heading', { name: 'Reset Password' })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Send Reset Code' })).toBeVisible();

      // Unroute so subsequent requests interact with real backend challenge
      await page.unroute('**/auth/forgot-password/send-otp');

      // 4. Verify browser Back/Forward hygiene: Back goes to login identifier, Forward returns to recovery
      await page.goBack();
      await expect(page.getByPlaceholder('Email or phone number')).toBeVisible();

      await page.goForward();
      await expect(page.getByRole('heading', { name: 'Reset Password' })).toBeVisible();

      // 5. Complete fresh recovery with real OTP
      await page.getByPlaceholder(/email.*phone/i).fill(email);
      await page.getByRole('button', { name: 'Send Reset Code' }).click();

      await expect(page.getByRole('button', { name: 'Reset Password' })).toBeVisible();
      // Verify stale fields were cleared
      await expect(page.getByPlaceholder('6-digit code')).toHaveValue('');
      await expect(page.getByPlaceholder('Enter new password', { exact: true })).toHaveValue('');

      // Fetch new real OTP from inbox
      const freshOtp = await getLatestInboxOtp(email, offset);
      await page.getByPlaceholder('6-digit code').fill(freshOtp);
      await page.getByPlaceholder('Enter new password', { exact: true }).fill(newPassword);
      await page.getByPlaceholder('Re-enter new password', { exact: true }).fill(newPassword);
      await page.getByRole('button', { name: 'Reset Password' }).click();

      // Password updated successfully
      await expect(page.getByRole('heading', { name: 'Password Updated' })).toBeVisible();

      // Sign in works with new password
      await page.getByRole('button', { name: 'Sign In' }).click();
      await page.getByPlaceholder('Email or phone number').fill(email);
      await page.getByRole('button', { name: 'Continue' }).click();
      await page.locator('input[type=password]').fill(newPassword);
      await page.getByRole('button', { name: 'Sign In' }).click();
      await expect(page).toHaveURL(/\/profile$/);
    } finally {
      await db.end();
    }
  });

  test('10: Backend RESET_CHALLENGE_EXPIRED response immediately offers Start New Reset without waiting for browser countdown', async ({ page }) => {
    await page.goto('/login');
    await page.getByPlaceholder('Email or phone number').fill('expiry-ux@example.test');
    await page.getByRole('button', { name: 'Continue' }).click();
    await page.getByRole('button', { name: 'Forgot password?' }).click();

    // 1. Initial request gives a challenge that claims to expire in 30 minutes
    await page.route('**/auth/forgot-password/send-otp', async (route) => {
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'RESET_CODE_SENT',
          challengeId: 'c-long-expiry',
          serverTime: new Date().toISOString(),
          challengeExpiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
          nextResendAt: new Date(Date.now() + 30 * 1000).toISOString(),
          resendsRemaining: 5,
        }),
      });
    });

    await page.getByRole('button', { name: 'Send Reset Code' }).click();

    // Verify reset-verify step is shown with "Reset Password" submit button
    await expect(page.getByRole('button', { name: 'Reset Password' })).toBeVisible();

    // Fill form
    await page.getByPlaceholder('6-digit code').fill('123456');
    await page.getByPlaceholder('Enter new password', { exact: true }).fill('NewSecurePassword123!');
    await page.getByPlaceholder('Re-enter new password', { exact: true }).fill('NewSecurePassword123!');

    // 2. Route reset submission to return 410 RESET_CHALLENGE_EXPIRED from backend
    await page.route('**/auth/forgot-password/reset', async (route) => {
      await route.fulfill({
        status: 410,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'RESET_CHALLENGE_EXPIRED',
          message: 'This password-reset session has expired.',
          serverTime: new Date().toISOString(),
        }),
      });
    });

    await page.getByRole('button', { name: 'Reset Password' }).click();

    // Error is displayed
    await expect(page.getByText('This password reset session has expired. Please start again.')).toBeVisible();

    // Immediately "Start New Reset" button appears without waiting for browser's 30-minute countdown!
    const startNewResetBtn = page.getByRole('button', { name: 'Start New Reset' }).first();
    await expect(startNewResetBtn).toBeVisible();
    await expect(page.getByRole('button', { name: 'Reset Password' })).toHaveCount(0);

    // Clicking "Start New Reset" navigates back to reset-request step with clean form
    await startNewResetBtn.click();
    await expect(page.getByRole('heading', { name: 'Reset Password' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Send Reset Code' })).toBeVisible();
  });
});
