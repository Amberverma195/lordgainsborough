import { test, expect } from '@playwright/test';

test.describe('Legal Consent & Document Access Flow', () => {
  test('01: populated signup form preserves all unsent input fields when opening and returning from Terms of Service', async ({
    page,
    context,
  }) => {
    await page.goto('/signup');

    // Populate all fields of the signup form
    const fullName = 'Lord Gainsborough Guest';
    const phone = '+1 (519)-555-0155';
    const email = 'guest-consent@example.test';
    const password = 'StrictPassword123!';

    await page.getByPlaceholder('Enter your full name').fill(fullName);
    await page.getByPlaceholder('+1 (123)-456-7890').fill(phone);
    await page.getByPlaceholder('Enter email address').fill(email);
    await page.getByPlaceholder('Enter Password', { exact: true }).fill(password);
    await page.getByPlaceholder('Re-enter password', { exact: true }).fill(password);

    // Verify initial values prior to opening document
    await expect(page.getByPlaceholder('Enter your full name')).toHaveValue(fullName);
    await expect(page.getByPlaceholder('+1 (123)-456-7890')).toHaveValue(phone);
    await expect(page.getByPlaceholder('Enter email address')).toHaveValue(email);
    await expect(page.getByPlaceholder('Enter Password', { exact: true })).toHaveValue(password);
    await expect(page.getByPlaceholder('Re-enter password', { exact: true })).toHaveValue(password);

    // Locate the Terms of Service link
    const termsLink = page.getByRole('link', { name: 'Terms of Service' });
    await expect(termsLink).toBeVisible();
    await expect(termsLink).toHaveAttribute('href', '/terms');
    await expect(termsLink).toHaveAttribute('target', '_blank');
    await expect(termsLink).toHaveAttribute('rel', 'noopener noreferrer');

    // Click Terms of Service and wait for new tab to open
    const [termsTab] = await Promise.all([
      context.waitForEvent('page'),
      termsLink.click(),
    ]);

    await termsTab.waitForLoadState('domcontentloaded');
    await expect(termsTab).toHaveURL(/\/terms$/);
    await expect(termsTab.getByRole('heading', { name: 'Terms of Service' })).toBeVisible();
    await expect(termsTab.getByText(/Version:\s*terms-v1\.0/i)).toBeVisible();

    // Close the document tab and return focus to the signup tab
    await termsTab.close();
    await page.bringToFront();

    // Verify that ALL unsent form fields remain completely intact
    await expect(page.getByPlaceholder('Enter your full name')).toHaveValue(fullName);
    await expect(page.getByPlaceholder('+1 (123)-456-7890')).toHaveValue(phone);
    await expect(page.getByPlaceholder('Enter email address')).toHaveValue(email);
    await expect(page.getByPlaceholder('Enter Password', { exact: true })).toHaveValue(password);
    await expect(page.getByPlaceholder('Re-enter password', { exact: true })).toHaveValue(password);
  });

  test('02: populated signup form preserves all unsent input fields when opening and returning from Privacy Policy', async ({
    page,
    context,
  }) => {
    await page.goto('/signup');

    const fullName = 'Lady Gainsborough Guest';
    const phone = '+1 (519)-555-0166';
    const email = 'privacy-guest@example.test';
    const password = 'StrictPassword456!';

    await page.getByPlaceholder('Enter your full name').fill(fullName);
    await page.getByPlaceholder('+1 (123)-456-7890').fill(phone);
    await page.getByPlaceholder('Enter email address').fill(email);
    await page.getByPlaceholder('Enter Password', { exact: true }).fill(password);
    await page.getByPlaceholder('Re-enter password', { exact: true }).fill(password);

    // Locate the Privacy Policy link
    const privacyLink = page.getByRole('link', { name: 'Privacy Policy' });
    await expect(privacyLink).toBeVisible();
    await expect(privacyLink).toHaveAttribute('href', '/privacy');
    await expect(privacyLink).toHaveAttribute('target', '_blank');
    await expect(privacyLink).toHaveAttribute('rel', 'noopener noreferrer');

    // Click Privacy Policy and wait for new tab to open
    const [privacyTab] = await Promise.all([
      context.waitForEvent('page'),
      privacyLink.click(),
    ]);

    await privacyTab.waitForLoadState('domcontentloaded');
    await expect(privacyTab).toHaveURL(/\/privacy$/);
    await expect(privacyTab.getByRole('heading', { name: 'Privacy Policy' })).toBeVisible();
    await expect(privacyTab.getByText(/Version:\s*privacy-v1\.0/i)).toBeVisible();

    // Close the document tab and return focus to the signup tab
    await privacyTab.close();
    await page.bringToFront();

    // Verify all fields remain intact
    await expect(page.getByPlaceholder('Enter your full name')).toHaveValue(fullName);
    await expect(page.getByPlaceholder('+1 (123)-456-7890')).toHaveValue(phone);
    await expect(page.getByPlaceholder('Enter email address')).toHaveValue(email);
    await expect(page.getByPlaceholder('Enter Password', { exact: true })).toHaveValue(password);
    await expect(page.getByPlaceholder('Re-enter password', { exact: true })).toHaveValue(password);
  });

  test('03: signup form requires explicit legal agreement before submitting', async ({ page }) => {
    await page.goto('/signup');

    await page.getByPlaceholder('Enter your full name').fill('No Consent User');
    await page.getByPlaceholder('+1 (123)-456-7890').fill('+1 (519)-555-0177');
    await page.getByPlaceholder('Enter email address').fill('noconsent@example.test');
    await page.getByPlaceholder('Enter Password', { exact: true }).fill('ValidPassword789!');
    await page.getByPlaceholder('Re-enter password', { exact: true }).fill('ValidPassword789!');

    // Do NOT check the terms checkbox
    const checkbox = page.getByRole('checkbox');
    await expect(checkbox).not.toBeChecked();

    // Click Create Account
    await page.getByRole('button', { name: 'Create Account' }).click();

    // Verify error is displayed and form does not transition to verify step
    await expect(
      page.getByText('Please agree to the Terms of Service and acknowledge the Privacy Policy to continue.')
    ).toBeVisible();
    await expect(page).toHaveURL(/\/signup$/);
    await expect(page.getByRole('heading', { name: 'Join the Club' })).toBeVisible();
  });

  test('04: passwords, password confirmation, and OTP codes are strictly excluded from browser storage', async ({
    page,
  }) => {
    await page.goto('/signup');

    const secretPassword = 'ConfidentialPassword999!';
    await page.getByPlaceholder('Enter your full name').fill('Secret Test User');
    await page.getByPlaceholder('+1 (123)-456-7890').fill('+1 (519)-555-0188');
    await page.getByPlaceholder('Enter email address').fill('secrets@example.test');
    await page.getByPlaceholder('Enter Password', { exact: true }).fill(secretPassword);
    await page.getByPlaceholder('Re-enter password', { exact: true }).fill(secretPassword);

    // Evaluate both localStorage and sessionStorage
    const storageAudit = await page.evaluate((secret) => {
      const leaks: string[] = [];
      const stores = [
        { name: 'localStorage', storage: localStorage },
        { name: 'sessionStorage', storage: sessionStorage },
      ];

      for (const { name, storage } of stores) {
        for (let i = 0; i < storage.length; i++) {
          const key = storage.key(i)!;
          const val = storage.getItem(key) || '';
          if (val.includes(secret)) {
            leaks.push(`${name} key "${key}" contains raw secret`);
          }
          if (key.toLowerCase().includes('password') || key.toLowerCase().includes('otp')) {
            leaks.push(`${name} contains forbidden key name "${key}"`);
          }
        }
      }
      return leaks;
    }, secretPassword);

    expect(storageAudit).toEqual([]);
  });
});
