import { adaptOriginForRuntime, resolveMediaUrl } from '../config';

describe('API origin configuration', () => {
  it('rewrites loopback only for an Android emulator', () => {
    expect(adaptOriginForRuntime('http://localhost:8082', { platform: 'android', isPhysicalDevice: false }))
      .toBe('http://10.0.2.2:8082');
    expect(adaptOriginForRuntime('http://127.0.0.1:8082', { platform: 'ios', isPhysicalDevice: false }))
      .toBe('http://127.0.0.1:8082');
    expect(adaptOriginForRuntime('http://localhost:8082', { platform: 'android', isPhysicalDevice: true }))
      .toBe('http://localhost:8082');
  });

  it('sends uploaded menu images to the API origin and leaves absolute URLs unchanged', () => {
    const origins = { api: 'http://10.0.2.2:8082', asset: 'http://10.0.2.2:3000' };
    expect(resolveMediaUrl('/uploads/menu/pie.jpg', origins)).toBe('http://10.0.2.2:8082/uploads/menu/pie.jpg');
    expect(resolveMediaUrl('/menu/pie.jpg', origins)).toBe('http://10.0.2.2:3000/menu/pie.jpg');
    expect(resolveMediaUrl('https://cdn.example/pie.jpg', origins)).toBe('https://cdn.example/pie.jpg');
    expect(resolveMediaUrl('', origins)).toBeNull();
  });
});
