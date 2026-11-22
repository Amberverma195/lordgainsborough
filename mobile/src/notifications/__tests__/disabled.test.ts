import {
  enableOrderAlerts,
  orderAlertsAvailable,
  pushDeviceKey,
} from '../orderAlerts';
it('keeps unverified builds disabled before requesting permission or registration', async () => {
  expect(orderAlertsAvailable).toBe(false);
  await expect(enableOrderAlerts('a@example.com', 'token')).rejects.toThrow(
    'not enabled',
  );
  expect(pushDeviceKey('a@example.com')).not.toEqual(
    pushDeviceKey('b@example.com'),
  );
});
