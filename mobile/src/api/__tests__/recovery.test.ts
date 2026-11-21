import { buildPasswordResetSend, buildSignupOtpSend, buildSignupVerify } from '../recovery';

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
      termsAccepted: true,
      termsVersion: 'terms-v1.0',
      privacyVersion: 'privacy-v1.1',
    });
    expect(request.path).toBe('/api/auth/signup/send-otp');
    expect(request.body.email).toBe('guest@example.com');
    expect(request.body.termsAccepted).toBe(true);
    expect(request.body.termsVersion).toBe('terms-v1.0');
    expect(request.body.privacyVersion).toBe('privacy-v1.1');
    expect(request.headers).toEqual({
      'Idempotency-Key': 'idem-1',
      'X-Captcha-Token': 'captcha-token',
      'X-Signup-Attempt-Token': 'attempt-token',
    });
  });

  it('does not silently default termsAccepted to true when false or missing', () => {
    const sendRequest = buildSignupOtpSend({
      email: 'user@example.com',
      phone: '5195550100',
      name: 'User',
      password: 'Secret1!',
      termsAccepted: false,
      termsVersion: 'terms-v1.0',
      privacyVersion: 'privacy-v1.1',
    });
    expect(sendRequest.body.termsAccepted).toBe(false);

    // Runtime check: even if cast without termsAccepted, it evaluates to false
    const untypedInput = {
      email: 'user@example.com',
      phone: '5195550100',
      name: 'User',
      password: 'Secret1!',
      termsVersion: 'terms-v1.0',
      privacyVersion: 'privacy-v1.1',
    } as any;
    expect(buildSignupOtpSend(untypedInput).body.termsAccepted).toBe(false);

    const verifyRequest = buildSignupVerify({
      email: 'user@example.com',
      otp: '123456',
      name: 'User',
      phone: '5195550100',
      password: 'Secret1!',
      termsAccepted: false,
      termsVersion: 'terms-v1.0',
      privacyVersion: 'privacy-v1.1',
    });
    expect(verifyRequest.body.termsAccepted).toBe(false);

    const untypedVerifyInput = {
      email: 'user@example.com',
      otp: '123456',
      name: 'User',
      phone: '5195550100',
      password: 'Secret1!',
      termsVersion: 'terms-v1.0',
      privacyVersion: 'privacy-v1.1',
    } as any;
    expect(buildSignupVerify(untypedVerifyInput).body.termsAccepted).toBe(false);
  });

  it('targets the existing password reset endpoint', () => {
    expect(buildPasswordResetSend({ email: 'Guest@Example.com', idempotencyKey: 'reset-1' })).toEqual({
      path: '/api/auth/forgot-password/send-otp',
      headers: { 'Idempotency-Key': 'reset-1' },
      body: { email: 'guest@example.com' },
    });
  });
});
