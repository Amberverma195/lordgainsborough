import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', workers: 1, fullyParallel: false, timeout: 120_000,
  expect: { timeout: 15_000 }, retries: 0,
  use: { baseURL: 'http://localhost:3000', browserName: 'chromium', viewport: { width: 1440, height: 900 },
    trace: 'on', screenshot: 'only-on-failure', video: 'retain-on-failure', actionTimeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }], ['json', { outputFile: '.e2e/results.json' }]],
});
