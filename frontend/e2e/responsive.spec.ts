import { test, expect } from '@playwright/test';

const VIEWPORTS = [
  { width: 320, height: 568, name: '320px - iPhone SE (narrow)' },
  { width: 360, height: 640, name: '360px - Android Compact' },
  { width: 375, height: 667, name: '375px - iPhone 8 / SE' },
  { width: 390, height: 844, name: '390px - iPhone 12/13/14' },
  { width: 414, height: 896, name: '414px - iPhone Plus / XR' },
  { width: 428, height: 926, name: '428px - iPhone Pro Max' },
  { width: 768, height: 1024, name: '768px - Tablet Portrait' },
  { width: 1024, height: 768, name: '1024px - Tablet Landscape' },
  { width: 1440, height: 900, name: '1440px - Desktop' },
];

test.describe('Responsive Viewports & Overflow Diagnostics', () => {
  for (const vp of VIEWPORTS) {
    test(`Viewport ${vp.name}: zero horizontal overflow and stable elements`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      await page.goto('/', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(500);

      // 1. Assert documentElement.scrollWidth <= window.innerWidth + 1
      const overflowMetrics = await page.evaluate(() => {
        const scrollWidth = document.documentElement.scrollWidth;
        const innerWidth = window.innerWidth;
        const bodyScrollWidth = document.body.scrollWidth;
        return {
          scrollWidth,
          innerWidth,
          bodyScrollWidth,
          hasHorizontalOverflow: scrollWidth > innerWidth + 1 || bodyScrollWidth > innerWidth + 1,
        };
      });

      expect(overflowMetrics.hasHorizontalOverflow).toBe(false);
      expect(overflowMetrics.scrollWidth).toBeLessThanOrEqual(vp.width + 1);

      // 2. Navbar stays within viewport boundaries
      const navbarBounds = await page.locator('nav').boundingBox();
      expect(navbarBounds).not.toBeNull();
      if (navbarBounds) {
        expect(navbarBounds.x).toBeGreaterThanOrEqual(-1);
        expect(navbarBounds.x + navbarBounds.width).toBeLessThanOrEqual(vp.width + 1);
      }

      // 3. Test vertical scrolling: ensure window.scrollX remains 0 (no horizontal shift)
      await page.evaluate(() => window.scrollTo(0, 400));
      await page.waitForTimeout(300);

      const scrollState = await page.evaluate(() => ({
        scrollX: window.scrollX,
        scrollY: window.scrollY,
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
      }));

      expect(scrollState.scrollX).toBe(0);
      expect(scrollState.scrollWidth).toBeLessThanOrEqual(vp.width + 1);

      // Scroll further down through the philosophy cards and featured dish
      await page.evaluate(() => window.scrollTo(0, 1000));
      await page.waitForTimeout(300);

      const deepScrollState = await page.evaluate(() => ({
        scrollX: window.scrollX,
        scrollWidth: document.documentElement.scrollWidth,
        innerWidth: window.innerWidth,
      }));

      expect(deepScrollState.scrollX).toBe(0);
      expect(deepScrollState.scrollWidth).toBeLessThanOrEqual(vp.width + 1);
    });
  }

  test('Vintage Cellar is completely removed and remaining 2 cards are displayed', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    // Assert "Vintage Cellar" is NOT on the page
    const vintageCellar = page.getByText('Vintage Cellar', { exact: false });
    await expect(vintageCellar).toHaveCount(0);

    // Assert "Gourmet Cuisine" and "Unrivaled Service" are present
    await expect(page.getByRole('heading', { name: 'Gourmet Cuisine' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Unrivaled Service' })).toBeVisible();

    // Verify exactly one footer exists (no duplicate footer)
    const footers = page.locator('footer');
    await expect(footers).toHaveCount(1);
  });

  test('Mobile hero image is visually stable and does not scale during scroll', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(400);

    // Inspect initial hero image wrapper transform / dimensions
    const initialTransform = await page.locator('[class*="heroImageWrapper"]').evaluate((el) => {
      return window.getComputedStyle(el).transform;
    });

    // Scroll down 300px
    await page.evaluate(() => window.scrollTo(0, 300));
    await page.waitForTimeout(300);

    const scrolledTransform = await page.locator('[class*="heroImageWrapper"]').evaluate((el) => {
      return window.getComputedStyle(el).transform;
    });

    // On mobile, transform should remain 'none' (or stable, not zoomed/drifted)
    expect(scrolledTransform).toBe(initialTransform);
  });

  test('Mobile navigation drawer opens smoothly without causing horizontal shift', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 640 });
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    const toggle = page.getByRole('button', { name: /Open navigation/i });
    await expect(toggle).toBeVisible();
    await toggle.click();

    const mobileNav = page.locator('#mobile-navigation');
    await expect(mobileNav).toBeVisible();

    // Verify "Book a Table" is accessible in the mobile drawer
    await expect(mobileNav.getByRole('link', { name: 'Book a Table' })).toBeVisible();

    // Verify no horizontal overflow with drawer open
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);
    expect(overflow).toBe(true);

    // Close navigation
    const closeBtn = page.getByRole('button', { name: /Close navigation/i });
    await closeBtn.click();
    await expect(mobileNav).toBeHidden();
  });
});
