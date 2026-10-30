import { test, expect } from '@playwright/test';

test.describe('Reservation Flow & Status Presentation', () => {
  test('Reserve page clearly indicates requests are pending host confirmation', async ({ page }) => {
    await page.goto('/reserve');

    // Page title and notice
    await expect(page.getByRole('heading', { name: /Request a Table/i })).toBeVisible();
    await expect(page.getByText(/Reservations are submitted as requests with Pending status/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /Submit Reservation Request/i })).toBeVisible();

    // Verify misleading text is NOT present
    await expect(page.getByText('Reservation Confirmed', { exact: false })).toHaveCount(0);
    await expect(page.getByText('is booked', { exact: false })).toHaveCount(0);
  });

  test('Correctly assigns day-of-week hours without UTC shift (Sunday vs Monday vs Friday)', async ({ page }) => {
    await page.goto('/reserve');

    const dateInput = page.locator('input[name="date"]');
    const timeSelect = page.locator('select[name="time"]');

    // 1. Sunday test: 2026-11-15 is Sunday (10 AM - 6 PM)
    await dateInput.fill('2026-11-15');
    await expect(page.getByText(/Sunday hours: 10:00 AM – 6:00 PM/i)).toBeVisible();

    const sundayOptions = await timeSelect.locator('option').allInnerTexts();
    expect(sundayOptions).toContain('10:00 AM');
    expect(sundayOptions).toContain('6:00 PM');
    // Sunday should NOT have 9:00 AM or 8:00 PM
    expect(sundayOptions).not.toContain('9:00 AM');
    expect(sundayOptions).not.toContain('8:00 PM');

    // 2. Monday test: 2026-11-16 is Monday (9 AM - 8 PM)
    await dateInput.fill('2026-11-16');
    await expect(page.getByText(/Monday – Thursday hours: 9:00 AM – 8:00 PM/i)).toBeVisible();

    const mondayOptions = await timeSelect.locator('option').allInnerTexts();
    expect(mondayOptions).toContain('9:00 AM');
    expect(mondayOptions).toContain('8:00 PM');
    // Monday should NOT have 8:30 PM (Fri/Sat only)
    expect(mondayOptions).not.toContain('8:30 PM');

    // 3. Friday test: 2026-11-20 is Friday (9 AM - 8:30 PM)
    await dateInput.fill('2026-11-20');
    await expect(page.getByText(/Friday & Saturday hours: 9:00 AM – 8:30 PM/i)).toBeVisible();

    const fridayOptions = await timeSelect.locator('option').allInnerTexts();
    expect(fridayOptions).toContain('9:00 AM');
    expect(fridayOptions).toContain('8:30 PM');

    // 4. Switching from Friday to Sunday clears incompatible selected time
    await timeSelect.selectOption({ value: '20:30' });
    expect(await timeSelect.inputValue()).toBe('20:30');

    await dateInput.fill('2026-11-22'); // Sunday
    expect(await timeSelect.inputValue()).toBe(''); // cleared because 20:30 is closed on Sunday
  });

  test('Submitting reservation displays "Reservation Request Received" with pending status and review notice', async ({ page }) => {
    // Intercept POST /api/reservations to return mock 201 response
    await page.route('**/api/reservations', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 'res-test-123',
            name: 'Alexander Wright',
            email: 'alex@example.com',
            phone: '(519) 555-1234',
            date: '2026-11-15',
            time: '17:30',
            partySize: 4,
            specialRequests: 'Window table please',
            status: 'PENDING',
            createdAt: new Date().toISOString(),
          }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/reserve');

    // Fill the form for Sunday 2026-11-15 at 17:30 (5:30 PM)
    await page.locator('input[name="date"]').fill('2026-11-15');
    await page.locator('select[name="time"]').selectOption({ value: '17:30' });
    await page.locator('select[name="partySize"]').selectOption({ value: '4' });
    await page.locator('input[name="name"]').fill('Alexander Wright');
    await page.locator('input[name="phone"]').fill('5195551234');
    await page.locator('input[name="email"]').fill('alex@example.com');
    await page.locator('textarea[name="specialRequests"]').fill('Window table please');

    // Submit
    const submitBtn = page.getByRole('button', { name: /Submit Reservation Request/i });
    await expect(submitBtn).toBeEnabled();
    await submitBtn.click();

    // Verify Success Screen presentation
    await expect(page.getByRole('heading', { name: 'Reservation Request Received' })).toBeVisible();
    await expect(page.getByText('Status: Pending Confirmation')).toBeVisible();
    await expect(page.getByText(/We have received your booking request/i)).toBeVisible();

    // Verify details
    await expect(page.getByText('2026-11-15 at 17:30')).toBeVisible();
    await expect(page.getByText('4 Guests')).toBeVisible();

    // Verify explicit non-guaranteed notice
    await expect(page.getByText(/Table Availability Review in Progress/i)).toBeVisible();
    await expect(page.getByText(/Your reservation is not yet confirmed/i)).toBeVisible();
    await expect(page.getByText(/Our host team reviews all requests against dining room capacity/i)).toBeVisible();

    // Verify misleading text is strictly absent
    await expect(page.getByText('Reservation Confirmed', { exact: false })).toHaveCount(0);
    await expect(page.getByText('is booked', { exact: false })).toHaveCount(0);

    // Verify actions
    await expect(page.getByRole('link', { name: 'Return to Home' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Submit Another Request' })).toBeVisible();
  });

  test('Accessing /reservations cleanly redirects to /reserve', async ({ page }) => {
    await page.goto('/reservations');
    await page.waitForURL('**/reserve');
    expect(page.url()).toContain('/reserve');
  });
});
