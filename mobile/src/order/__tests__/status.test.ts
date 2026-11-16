import { isActiveOrderStatus } from '../status';
import { isActiveOrderStatus as displayIsActive } from '../../format/display';

describe('Order status active check', () => {
  it('identifies in-progress statuses as active', () => {
    expect(isActiveOrderStatus('NEW')).toBe(true);
    expect(isActiveOrderStatus('ACCEPTED')).toBe(true);
    expect(isActiveOrderStatus('READY')).toBe(true);
    expect(isActiveOrderStatus('new')).toBe(true);
    expect(isActiveOrderStatus(' accepted ')).toBe(true);
    expect(isActiveOrderStatus('ready')).toBe(true);
  });

  it('identifies terminal statuses as inactive', () => {
    expect(isActiveOrderStatus('PICKED_UP')).toBe(false);
    expect(isActiveOrderStatus('NO_SHOW')).toBe(false);
    expect(isActiveOrderStatus('picked_up')).toBe(false);
    expect(isActiveOrderStatus('no_show')).toBe(false);
  });

  it('treats empty, null, and undefined values as inactive', () => {
    expect(isActiveOrderStatus(null)).toBe(false);
    expect(isActiveOrderStatus(undefined)).toBe(false);
    expect(isActiveOrderStatus('')).toBe(false);
    expect(isActiveOrderStatus('   ')).toBe(false);
  });

  it('is identically re-exported from display', () => {
    expect(displayIsActive('NEW')).toBe(true);
    expect(displayIsActive('PICKED_UP')).toBe(false);
  });
});
