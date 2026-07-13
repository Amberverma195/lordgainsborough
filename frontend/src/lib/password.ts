export type PasswordStrength = 'empty' | 'weak' | 'fair' | 'good' | 'strong';

export interface PasswordRequirement {
  id: string;
  label: string;
  met: boolean;
}

export interface PasswordValidationResult {
  valid: boolean;
  errors: string[];
  requirements: PasswordRequirement[];
  strength: PasswordStrength;
  score: number;
}

const MIN_LENGTH = 8;
const MAX_LENGTH = 128;
const SPECIAL_CHAR_PATTERN = /[!@#$%^&*()_+\-=[\]{};':"\\|,.<>/?`~]/;

/** Strip control chars, zero-width chars, and cap length. */
export function sanitizePasswordInput(raw: string): string {
  return raw
    .replace(/[\u0000-\u001F\u007F-\u009F]/g, '')
    .replace(/[\u200B-\u200D\uFEFF\u202A-\u202E]/g, '')
    .slice(0, MAX_LENGTH);
}

export function getPasswordRequirements(password: string): PasswordRequirement[] {
  return [
    {
      id: 'length',
      label: `At least ${MIN_LENGTH} characters`,
      met: password.length >= MIN_LENGTH,
    },
    {
      id: 'lower',
      label: 'One lowercase letter',
      met: /[a-z]/.test(password),
    },
    {
      id: 'upper',
      label: 'One uppercase letter',
      met: /[A-Z]/.test(password),
    },
    {
      id: 'number',
      label: 'One number',
      met: /\d/.test(password),
    },
    {
      id: 'special',
      label: 'One special character (!@#$…)',
      met: SPECIAL_CHAR_PATTERN.test(password),
    },
  ];
}

export function getPasswordStrength(password: string): { strength: PasswordStrength; score: number } {
  if (!password) {
    return { strength: 'empty', score: 0 };
  }

  let score = 0;
  if (password.length >= MIN_LENGTH) score += 1;
  if (password.length >= 12) score += 1;
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score += 1;
  if (/\d/.test(password)) score += 1;
  if (SPECIAL_CHAR_PATTERN.test(password)) score += 1;

  const capped = Math.min(score, 4);
  const strengthMap: PasswordStrength[] = ['weak', 'weak', 'fair', 'good', 'strong'];
  return { strength: strengthMap[capped], score: capped };
}

export function validatePassword(password: string): PasswordValidationResult {
  const sanitized = sanitizePasswordInput(password);
  const requirements = getPasswordRequirements(sanitized);
  const { strength, score } = getPasswordStrength(sanitized);
  const errors: string[] = [];

  if (!sanitized) {
    errors.push('Password is required.');
  } else {
    for (const req of requirements) {
      if (!req.met) {
        errors.push(req.label);
      }
    }
  }

  return {
    valid: sanitized.length > 0 && requirements.every(r => r.met),
    errors,
    requirements,
    strength,
    score,
  };
}

export function passwordsMatch(password: string, confirmPassword: string): boolean {
  const a = sanitizePasswordInput(password);
  const b = sanitizePasswordInput(confirmPassword);
  return a.length > 0 && a === b;
}

export function getPasswordValidationMessage(password: string, confirmPassword: string): string | null {
  const result = validatePassword(password);
  if (!result.valid) {
    return result.errors[0] ?? 'Password does not meet requirements.';
  }
  if (confirmPassword && !passwordsMatch(password, confirmPassword)) {
    return 'Passwords do not match.';
  }
  return null;
}
