import { test, expect } from '@playwright/test';
import { Client } from 'pg';
import { hashSync } from 'bcryptjs';
import { randomUUID, randomInt } from 'node:crypto';
import { readFile } from 'node:fs/promises';

test('HttpOnly session, CSRF protection, CSP, profile editing and logout', async ({ page, context }) => {
  const state = JSON.parse(await readFile('.e2e/state.json', 'utf8'));
  if (!/^lgb_e2e_[a-f0-9]{32}$/.test(state.database)) throw Error('Refusing non-E2E database');
  const db = new Client({ host: '127.0.0.1', port: state.port, database: state.database, user: 'postgres', password: 'postgres' });
  await db.connect();
  const email = `cookie-${randomUUID()}@example.test`, password = 'CookieFixture1!';
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await db.query("insert into users(id,name,email,password,role) values ($1,'Cookie Member',$2,$3,'MEMBER')", [randomUUID(), email, hashSync(password, 10)]);
    await page.addInitScript(() => localStorage.setItem('authToken', 'legacy-token-to-remove'));
    const navigation = await page.goto('/login');
    const csp = navigation!.headers()['content-security-policy'];
    expect(csp).toContain("'strict-dynamic'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).not.toContain("'unsafe-eval'");
    const scripts = csp.split(';').find(value => value.trim().startsWith('script-src'))!;
    expect(scripts).not.toContain("'unsafe-inline'");
    await page.getByPlaceholder('Email or phone number', { exact: true }).fill(email);
    await page.locator('input[type=password]').fill(password);
    const login = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/login');
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    expect((await (await login).json()).token).toBeUndefined();
    await expect(page.getByRole('heading', { name: 'Cookie Member', exact: true })).toBeVisible();
    const cookies = await context.cookies();
    const session = cookies.find(cookie => cookie.name === 'lgb_session')!;
    expect(session.httpOnly).toBe(true);
    expect(session.sameSite).toBe('Lax');
    const csrf = cookies.find(cookie => cookie.name === 'lgb_csrf')!.value;
    expect(await page.evaluate(() => document.cookie.includes('lgb_session='))).toBe(false);
    expect(await page.evaluate(() => localStorage.getItem('authToken'))).toBeNull();
    expect((await (await page.request.get('/api/auth/me')).json()).token).toBeUndefined();
    expect((await page.request.put('/api/auth/profile', { data: { name: 'Forged Name' } })).status()).toBe(403);
    expect((await page.request.put('/api/auth/profile', { data: { name: 'Forged Name' }, headers: {
      Origin: 'https://attacker.example', 'X-CSRF-Token': csrf,
    } })).status()).toBe(403);
    await page.getByRole('button', { name: 'Edit Profile', exact: true }).click();
    await page.locator('input[type=text]').fill('Updated Cookie Member');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Updated Cookie Member', exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Updated Cookie Member', exact: true })).toBeVisible();
    const second = await context.newPage();
    await second.goto('/profile');
    await expect(second.getByRole('heading', { name: 'My Account', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Logout', exact: true }).click();
    await page.getByRole('button', { name: 'Log Out', exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect((await context.cookies()).some(cookie => cookie.name === 'lgb_session')).toBe(false);
    expect((await second.request.get('/api/auth/me')).status()).toBe(401);
    expect(await second.evaluate(() => localStorage.getItem('sessionUser'))).toBeNull();
    expect(errors).toEqual([]);
  } finally { await db.end(); }
});

test('CSP blocks an untrusted parser-inserted script without a nonce', async ({ page }) => {
  await page.route('**/login', async route => {
    const response = await route.fetch();
    const html = await response.text();
    // Simulate HTML injection while retaining the server's enforced CSP response header.
    await route.fulfill({ response, body: html.replace('<head>', '<head><script>window.__cspProbe = true</script>') });
  });
  await page.goto('/login');
  expect(await page.evaluate(() => (window as Window & { __cspProbe?: boolean }).__cspProbe)).toBeUndefined();
});

test('administrator login still authorizes the admin page through the server cookie', async ({ page }) => {
  const state = JSON.parse(await readFile('.e2e/state.json', 'utf8'));
  await page.goto('/login');
  await page.getByPlaceholder('Email or phone number', { exact: true }).fill(state.adminEmail);
  await page.locator('input[type=password]').fill(state.adminPassword);
  await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  await expect(page).toHaveURL(/\/profile$/);
  await page.getByRole('link', { name: 'Admin Panel', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Admin sections' })).toBeVisible();
});

test('password reset remains usable without exposing a session JWT', async ({ page }) => {
  const state = JSON.parse(await readFile('.e2e/state.json', 'utf8'));
  const db = new Client({ host: '127.0.0.1', port: state.port, database: state.database, user: 'postgres', password: 'postgres' });
  if (!/^lgb_e2e_[a-f0-9]{32}$/.test(state.database)) throw Error('Refusing non-E2E database');
  await db.connect();
  const email = `cookie-reset-${randomUUID()}@example.test`, password = 'UpdatedCookie1!';
  try {
    await db.query("insert into users(id,name,email,password,role) values ($1,'Reset Cookie Member',$2,$3,'MEMBER')", [randomUUID(), email, hashSync('OldCookie1!', 10)]);
    await page.goto('/forgot-password');
    await page.getByPlaceholder('Enter email address', { exact: true }).fill(email);
    await page.getByRole('button', { name: 'Send Reset Code', exact: true }).click();
    let code = '';
    await expect.poll(async () => {
      const messages = await (await fetch('http://127.0.0.1:8025/messages')).json();
      code = messages.findLast((message: { to: string[] }) => message.to.includes(email))?.code || '';
      return code;
    }).toMatch(/^\d{6}$/);
    await page.getByPlaceholder('6-digit code', { exact: true }).fill(code);
    await page.getByPlaceholder('Enter new password', { exact: true }).fill(password);
    await page.getByPlaceholder('Re-enter new password', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Reset Password', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Password Updated', exact: true })).toBeVisible();
    await page.goto('/login');
    await page.getByPlaceholder('Email or phone number', { exact: true }).fill(email);
    await page.locator('input[type=password]').fill(password);
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Reset Cookie Member', exact: true })).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('authToken'))).toBeNull();
  } finally { await db.end(); }
});

test('signup, cart persistence, checkout, and live order updates use the cookie session', async ({ page, context }) => {
  const email = `cookie-signup-${randomUUID()}@example.test`;
  const password = 'CookieSignup1!', name = 'Cookie Signup Member';
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/signup');
  await page.getByPlaceholder('Enter your full name', { exact: true }).fill(name);
  await page.getByPlaceholder('+1 (123)-456-7890', { exact: true }).fill(`519${randomInt(2000000, 9999999)}`);
  await page.getByPlaceholder('Enter email address', { exact: true }).fill(email);
  await page.getByPlaceholder('Enter Password', { exact: true }).fill(password);
  await page.getByPlaceholder('Re-enter password', { exact: true }).fill(password);
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: 'Create Account', exact: true }).click();
  await expect(page).toHaveURL(/\/signup\/verify/);
  const challenge = await page.evaluate(() => JSON.parse(sessionStorage.getItem('signupOtpChallenge') || 'null'));
  expect(challenge.attemptToken).toBeTruthy();
  const statusUrl = `/api/auth/signup/otp-status?${new URLSearchParams({ email, challengeId: challenge.challengeId })}`;
  const anonymousStatus = await page.request.get(statusUrl);
  expect(anonymousStatus.status()).toBe(404);
  expect((await anonymousStatus.json()).challengeId).toBeUndefined();
  expect((await page.request.get(statusUrl, { headers: { 'X-Signup-Attempt-Token': 'wrong-token' } })).status()).toBe(404);
  const ownedStatus = await page.request.get(statusUrl, { headers: { 'X-Signup-Attempt-Token': challenge.attemptToken } });
  expect(ownedStatus.status()).toBe(200);
  expect(ownedStatus.headers()['cache-control']).toBe('no-store');
  expect((await ownedStatus.json()).challengeId).toBe(challenge.challengeId);
  // Refresh must retain ownership and restore the original challenge metadata.
  await page.reload();
  await expect(page.getByRole('textbox', { name: 'Digit 1', exact: true })).toBeVisible();
  let code = '';
  await expect.poll(async () => {
    const messages = await (await fetch('http://127.0.0.1:8025/messages')).json();
    code = messages.findLast((message: { to: string[] }) => message.to.includes(email))?.code || '';
    return code;
  }).toMatch(/^\d{6}$/);
  for (let index = 0; index < 6; index++) await page.getByRole('textbox', { name: `Digit ${index + 1}`, exact: true }).fill(code[index]);
  const signup = page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/signup');
  await page.getByRole('button', { name: /Verify Email & Create Account|Create Account/i, exact: true }).click();
  expect((await (await signup).json()).token).toBeUndefined();
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
  expect((await context.cookies()).find(cookie => cookie.name === 'lgb_session')?.httpOnly).toBe(true);
  await page.goto('/menu');
  await expect(page.getByRole('heading', { name: 'Lord Gainsborough Menu', exact: true })).toBeVisible();
  const tomorrow = new Date(Date.now() + 86400000);
  const pickupDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Toronto' }).format(tomorrow);
  await page.locator('input[type=date]').fill(pickupDate);
  await page.getByRole('combobox').selectOption({ label: '12:00 PM' });
  const card = page.getByRole('heading', { name: 'Cheesy Garlic Bread', exact: true }).locator('../..');
  await card.getByRole('button', { name: 'Add to Cart', exact: true }).click();
  await page.getByRole('button', { name: 'Proceed to Checkout', exact: true }).click();
  await expect(page).toHaveURL(/\/checkout$/);
  await page.getByRole('button', { name: /Confirm Pick-up Order/ }).click();
  await expect(page).toHaveURL(/\/confirmation$/);
  const orderId = page.url().split('/').at(-2)!;
  await page.getByRole('link', { name: 'Check Order', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText('ORD-');
  const ticket = await page.request.get(`/api/orders/${orderId}/socket-ticket`);
  expect(ticket.status()).toBe(200);
  const { ticket: scopedTicket } = await ticket.json();
  expect((await page.request.get('http://127.0.0.1:8082/api/auth/me', { headers: { Authorization: `Bearer ${scopedTicket}` } })).status()).toBe(401);
  const accepted = await page.request.patch(`http://127.0.0.1:8082/api/kds/orders/${orderId}/status`, { data: { status: 'ACCEPTED' } });
  expect(accepted.status()).toBe(200);
  // No polling or navigation: the existing Socket.IO connection must deliver the update.
  await expect(page.getByText('In progress', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('authToken'))).toBeNull();
  expect(errors).toEqual([]);
});
