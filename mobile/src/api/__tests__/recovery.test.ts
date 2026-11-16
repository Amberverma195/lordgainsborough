import { buildPasswordResetSend, buildSignupOtpSend } from '../recovery';

describe('account recovery request builders', () => {
  it('keeps the signup attempt token and captcha header separate from the body secret', () => {
    const request = buildSignupOtpSend({
      email: ' Guest@Example.com ',
      phone: '5195550100',
      name: 'Guest',
      password: 'Secret1!',
      attemptToken: 'attempt-token',
      idempotencyKey: 'idem-1',
      captchaToken: 'captcha-token',
    });
    expect(request.path).toBe('/api/auth/signup/send-otp');
    expect(request.body.email).toBe('guest@example.com');
    expect(request.headers).toEqual({
      'Idempotency-Key': 'idem-1',
      'X-Captcha-Token': 'captcha-token',
      'X-Signup-Attempt-Token': 'attempt-token',
    });
  });

  it('targets the existing password reset endpoint', () => {
    expect(buildPasswordResetSend({ email: 'Guest@Example.com', idempotencyKey: 'reset-1' })).toEqual({
      path: '/api/auth/forgot-password/send-otp',
      headers: { 'Idempotency-Key': 'reset-1' },
      body: { email: 'guest@example.com' },
    });
  });
});
