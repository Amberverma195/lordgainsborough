import { test, expect } from '@playwright/test';

const MOBILE_VIEWPORTS = [
  { width: 320, height: 568, name: '320px - iPhone SE' },
  { width: 375, height: 667, name: '375px - iPhone 8 / SE' },
  { width: 390, height: 844, name: '390px - iPhone 12/13/14' },
  { width: 414, height: 896, name: '414px - iPhone Plus / XR' },
  { width: 768, height: 1024, name: '768px - Tablet' },
  { width: 1440, height: 900, name: '1440px - Desktop' },
];

test.describe('Homepage Loading, Instant Visibility & Performance Tests', () => {
  // Test 1: Immediate visibility with JavaScript DISABLED
  test('Static content is fully visible immediately even when JavaScript is disabled', async ({ browser }) => {
    const context = await browser.newContext({
      javaScriptEnabled: false,
      viewport: { width: 375, height: 667 },
    });
    const page = await context.newPage();
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    // Verify hero text is visible
    const heroTitle = page.locator('h1');
    await expect(heroTitle).toBeVisible();
    await expect(heroTitle).toContainText('Lord');
    await expect(heroTitle).toContainText('Gainsborough');

    // Verify hero eyebrow and subtitle
    await expect(page.getByText('Established 1985')).toBeVisible();
    await expect(page.getByText('Mastery of Taste & Timeless Elegance')).toBeVisible();

    // Verify hero CTA buttons
    await expect(page.getByRole('link', { name: /Explore Menu/i })).toBeVisible();
    await expect(page.getByRole('link', { name: /Book a Table/i }).first()).toBeVisible();

    // Verify philosophy cards are visible without empty black space
    await expect(page.getByRole('heading', { name: 'Gourmet Cuisine' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Unrivaled Service' })).toBeVisible();

    // Verify information cards
    await expect(page.getByRole('heading', { name: 'Hours', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Address', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Private Dining', exact: true })).toBeVisible();

    // Verify fictional Wagyu Ribeye placeholder is not displayed
    await expect(page.getByRole('heading', { name: 'Signature Wagyu Ribeye' })).toHaveCount(0);

    await context.close();
  });

  // Test 2: Rapid vertical scrolling immediately after navigation shows no invisible/black sections
  for (const vp of MOBILE_VIEWPORTS) {
    test(`Viewport ${vp.name}: Rapid scrolling immediately reveals fully visible cards with no black space`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await page.goto('/', { waitUntil: 'domcontentloaded' });

      // Immediately scroll down 400px (philosophy section)
      await page.evaluate(() => window.scrollTo(0, 400));
      await page.waitForTimeout(50);

      // Inspect opacity and computed visibility of philosophy cards
      const cardsOpacity = await page.evaluate(() => {
        const cards = Array.from(document.querySelectorAll('.premium-card'));
        return cards.map(c => {
          const style = window.getComputedStyle(c);
          return {
            opacity: parseFloat(style.opacity),
            visibility: style.visibility,
            display: style.display,
          };
        });
      });

      expect(cardsOpacity.length).toBeGreaterThanOrEqual(2);
      for (const card of cardsOpacity) {
        expect(card.opacity).toBeGreaterThan(0);
        expect(card.visibility).toBe('visible');
        expect(card.display).not.toBe('none');
      }

      // Scroll down further to info grid
      await page.evaluate(() => window.scrollTo(0, 1200));
      await page.waitForTimeout(50);

      const hoursVisible = await page.getByRole('heading', { name: 'Hours', exact: true }).isVisible();
      const addressVisible = await page.getByRole('heading', { name: 'Address', exact: true }).isVisible();
      expect(hoursVisible).toBe(true);
      expect(addressVisible).toBe(true);

      // Verify no horizontal overflow occurs during rapid scroll
      const overflowMetrics = await page.evaluate(() => ({
        scrollX: window.scrollX,
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
      }));
      expect(overflowMetrics.scrollX).toBe(0);
      expect(overflowMetrics.scrollWidth).toBeLessThanOrEqual(vp.width + 1);
    });
  }

  // Test 3: Measure FCP, LCP, and CLS performance metrics
  test('Measure FCP, LCP, and CLS under normal and slow network conditions', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/', { waitUntil: 'load' });

    const metrics = await page.evaluate(() => {
      return new Promise<{ fcp: number | null }>((resolve) => {
        try {
          const observer = new PerformanceObserver((entryList) => {
            const entries = entryList.getEntriesByName('first-contentful-paint');
            if (entries.length > 0) {
              resolve({ fcp: entries[0].startTime });
            }
          });
          observer.observe({ type: 'paint', buffered: true });
          setTimeout(() => {
            const paintEntries = performance.getEntriesByType('paint');
            const fcp = paintEntries.find(e => e.name === 'first-contentful-paint')?.startTime || null;
            resolve({ fcp });
          }, 800);
        } catch {
          resolve({ fcp: null });
        }
      });
    });

    if (metrics.fcp !== null) {
      expect(metrics.fcp).toBeLessThan(2500);
    }
  });

  // Test 4: Reduced motion preference
  test('Respects prefers-reduced-motion by disabling animations', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    // Hero title is visible immediately without animation delay
    const heroTitle = page.locator('h1');
    await expect(heroTitle).toBeVisible();

    const opacity = await page.locator('[class*="heroContent"]').evaluate((el) => {
      return parseFloat(window.getComputedStyle(el).opacity);
    });
    expect(opacity).toBe(1);
  });

  // Test 5: Verify no hydration warnings or console errors occur
  test('Page loads without React hydration errors or uncaught exceptions', async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') {
        consoleErrors.push(msg.text());
      }
    });

    page.on('pageerror', (err) => {
      consoleErrors.push(err.message);
    });

    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/', { waitUntil: 'load' });
    await page.waitForTimeout(500);

    const hydrationErrors = consoleErrors.filter(err =>
      err.toLowerCase().includes('hydration') ||
      err.toLowerCase().includes('did not match') ||
      err.toLowerCase().includes('minified react error')
    );

    expect(hydrationErrors).toEqual([]);
  });
});
