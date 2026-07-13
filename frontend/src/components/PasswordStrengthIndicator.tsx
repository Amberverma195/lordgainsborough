'use client';

import React from 'react';
import { Check, X } from 'lucide-react';
import styles from './PasswordStrengthIndicator.module.css';
import {
  getPasswordRequirements,
  getPasswordStrength,
  passwordsMatch,
  type PasswordStrength,
} from '@/lib/password';

const STRENGTH_LABEL: Record<PasswordStrength, string> = {
  empty: 'Enter a password',
  weak: 'Weak',
  fair: 'Fair',
  good: 'Good',
  strong: 'Strong',
};

const STRENGTH_CLASS: Record<PasswordStrength, string> = {
  empty: styles.strengthEmpty,
  weak: styles.strengthWeak,
  fair: styles.strengthFair,
  good: styles.strengthGood,
  strong: styles.strengthStrong,
};

const BAR_COLORS: Record<PasswordStrength, string> = {
  empty: 'rgba(255, 255, 255, 0.08)',
  weak: '#ff6b6b',
  fair: '#ffb347',
  good: '#d4af37',
  strong: '#6bcf7f',
};

interface PasswordStrengthIndicatorProps {
  password: string;
  confirmPassword?: string;
  showRequirements?: boolean;
  /** When true, show "Passwords do not match" only if they differ (e.g. after submit). */
  showMatchError?: boolean;
}

export default function PasswordStrengthIndicator({
  password,
  confirmPassword = '',
  showRequirements = true,
  showMatchError = false,
}: PasswordStrengthIndicatorProps) {
  const { strength, score } = getPasswordStrength(password);
  const requirements = getPasswordRequirements(password);
  const matched = passwordsMatch(password, confirmPassword);
  const showMismatch =
    showMatchError && confirmPassword.length > 0 && !matched;

  if (!password && !showMismatch) {
    return null;
  }

  return (
    <div className={styles.wrapper} aria-live="polite">
      {password && (
        <div className={styles.strengthRow}>
          <div className={styles.strengthLabel}>
            <span>Password strength</span>
            <span className={STRENGTH_CLASS[strength]}>{STRENGTH_LABEL[strength]}</span>
          </div>
          <div className={styles.barTrack} role="meter" aria-valuenow={score} aria-valuemin={0} aria-valuemax={4}>
            {[0, 1, 2, 3].map(index => (
              <div
                key={index}
                className={`${styles.barSegment} ${index < score ? styles.barSegmentActive : ''}`}
                style={{
                  background: index < score ? BAR_COLORS[strength] : undefined,
                }}
              />
            ))}
          </div>
        </div>
      )}

      {showRequirements && password && (
        <ul className={styles.requirements}>
          {requirements.map(req => (
            <li
              key={req.id}
              className={`${styles.requirement} ${req.met ? styles.requirementMet : ''}`}
            >
              {req.met ? (
                <Check size={14} className={styles.requirementIcon} aria-hidden />
              ) : (
                <X size={14} className={styles.requirementIcon} aria-hidden />
              )}
              {req.label}
            </li>
          ))}
        </ul>
      )}

      {showMismatch && (
        <p className={`${styles.matchHint} ${styles.matchError}`}>
          Passwords do not match
        </p>
      )}
    </div>
  );
}
