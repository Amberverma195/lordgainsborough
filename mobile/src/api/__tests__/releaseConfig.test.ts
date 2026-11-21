const buildConfig = require('../../../app.config.js');
const keys = [
  'EAS_BUILD_PROFILE',
  'EXPO_PROJECT_ID',
  'EXPO_PUBLIC_API_ORIGIN',
  'EXPO_PUBLIC_SOCKET_ORIGIN',
  'EXPO_PUBLIC_ASSET_ORIGIN',
];
const saved = Object.fromEntries(keys.map((key) => [key, process.env[key]]));
afterEach(() => {
  for (const key of keys) {
    if (saved[key] === undefined) delete process.env[key];
    else process.env[key] = saved[key];
  }
});
it('rejects release builds without explicit HTTPS origins', () => {
  process.env.EAS_BUILD_PROFILE = 'production';
  delete process.env.EXPO_PUBLIC_API_ORIGIN;
  expect(() => buildConfig({ config: {} })).toThrow('EXPO_PUBLIC_API_ORIGIN');
});
it('rejects localhost and preserves package identifiers with configured HTTPS origins', () => {
  process.env.EAS_BUILD_PROFILE = 'preview';
  process.env.EXPO_PUBLIC_API_ORIGIN = 'http://localhost:8082';
  expect(() => buildConfig({ config: {} })).toThrow('HTTPS');
  process.env.EXPO_PUBLIC_API_ORIGIN = 'https://api.test.invalid';
  process.env.EXPO_PUBLIC_SOCKET_ORIGIN = 'https://socket.test.invalid';
  process.env.EXPO_PUBLIC_ASSET_ORIGIN = 'https://assets.test.invalid';
  process.env.EXPO_PROJECT_ID = '00000000-0000-4000-8000-000000000001';
  const config = buildConfig({
    config: {
      android: { package: 'com.lordgainsborough.customer' },
      ios: { bundleIdentifier: 'com.lordgainsborough.customer' },
    },
  });
  expect(config.android.package).toBe('com.lordgainsborough.customer');
  expect(config.ios.bundleIdentifier).toBe('com.lordgainsborough.customer');
  expect(config.extra.eas.projectId).toBe(process.env.EXPO_PROJECT_ID);
});
