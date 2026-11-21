import { describe, expect, it } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import * as crypto from 'crypto';
import { CURRENT_TERMS_VERSION, CURRENT_PRIVACY_VERSION } from './legalConsent';

interface PolicyRecord {
  id: string;
  type: 'TERMS' | 'PRIVACY';
  version: string;
  title: string;
  status: 'CURRENT' | 'SUPERSEDED' | 'DRAFT';
  effectiveDate: string;
  supersededDate?: string;
  supersededBy?: string;
  sourcePath: string | null;
  archivePath: string | null;
  contentSha256: string;
  notes: string;
}

interface PolicyRegistry {
  version: number;
  lastAudited: string;
  policies: PolicyRecord[];
}

function normalizeContent(raw: string): string {
  // Normalize Windows CRLF to standard Unix LF so hashes remain platform-independent
  return raw.replace(/\r\n/g, '\n');
}

function computeSha256(content: string): string {
  return crypto.createHash('sha256').update(content, 'utf8').digest('hex');
}

describe('Legal Policy Immutability & Content Hash Integrity', () => {
  const repoRoot = path.resolve(__dirname, '../../..');
  const registryPath = path.join(repoRoot, 'docs/legal/policy-registry.json');

  it('policy registry exists and is valid JSON', () => {
    expect(fs.existsSync(registryPath)).toBe(true);
    const raw = fs.readFileSync(registryPath, 'utf8');
    const registry: PolicyRegistry = JSON.parse(raw);
    expect(registry.policies.length).toBeGreaterThanOrEqual(2);
  });

  it('frontend legalConsent.ts constants match current registry entries', () => {
    const raw = fs.readFileSync(registryPath, 'utf8');
    const registry: PolicyRegistry = JSON.parse(raw);

    const currentTerms = registry.policies.find(p => p.type === 'TERMS' && p.status === 'CURRENT');
    const currentPrivacy = registry.policies.find(p => p.type === 'PRIVACY' && p.status === 'CURRENT');

    expect(currentTerms).toBeDefined();
    expect(currentPrivacy).toBeDefined();
    expect(CURRENT_TERMS_VERSION).toBe(currentTerms!.version);
    expect(CURRENT_PRIVACY_VERSION).toBe(currentPrivacy!.version);
  });

  it('all archived policy copies match their recorded SHA-256 content hashes', () => {
    const raw = fs.readFileSync(registryPath, 'utf8');
    const registry: PolicyRegistry = JSON.parse(raw);

    for (const policy of registry.policies) {
      if (policy.archivePath) {
        const fullArchivePath = path.join(repoRoot, policy.archivePath);
        expect(
          fs.existsSync(fullArchivePath),
          `Archived file for policy ${policy.id} must exist at ${policy.archivePath}`
        ).toBe(true);

        const archiveContent = fs.readFileSync(fullArchivePath, 'utf8');
        const computedHash = computeSha256(normalizeContent(archiveContent));

        expect(
          computedHash,
          `Archived content hash mismatch for ${policy.id}! Expected ${policy.contentSha256} but computed ${computedHash}`
        ).toBe(policy.contentSha256);
      }
    }
  });

  it('all active source legal pages match their authoritative SHA-256 hashes without silent drift', () => {
    const raw = fs.readFileSync(registryPath, 'utf8');
    const registry: PolicyRegistry = JSON.parse(raw);

    for (const policy of registry.policies) {
      if (policy.sourcePath && policy.status === 'CURRENT') {
        const fullSourcePath = path.join(repoRoot, policy.sourcePath);
        expect(
          fs.existsSync(fullSourcePath),
          `Source file for active policy ${policy.id} must exist at ${policy.sourcePath}`
        ).toBe(true);

        const sourceContent = fs.readFileSync(fullSourcePath, 'utf8');
        const normalized = normalizeContent(sourceContent);
        const computedHash = computeSha256(normalized);

        if (computedHash !== policy.contentSha256) {
          throw new Error(
            `IMMUTABILITY VIOLATION: Legal document at "${policy.sourcePath}" has been modified without bumping its version!\n` +
            `Expected SHA-256: ${policy.contentSha256}\n` +
            `Actual SHA-256:   ${computedHash}\n` +
            `To change legal wording: (1) Archive current version into docs/legal/archive/, ` +
            `(2) Bump version identifier to a new version, (3) Update docs/legal/policy-registry.json.`
          );
        }

        expect(computedHash).toBe(policy.contentSha256);
      }
    }
  });

  it('published legal pages display their registered version badges', () => {
    const termsPagePath = path.join(repoRoot, 'frontend/src/app/terms/page.tsx');
    const privacyPagePath = path.join(repoRoot, 'frontend/src/app/privacy/page.tsx');

    const termsContent = fs.readFileSync(termsPagePath, 'utf8');
    const privacyContent = fs.readFileSync(privacyPagePath, 'utf8');

    expect(termsContent).toContain('Version: terms-v1.0');
    expect(privacyContent).toContain('Version: privacy-v1.1');
  });

  it('superceded policies retain historical auditability and are not erased', () => {
    const raw = fs.readFileSync(registryPath, 'utf8');
    const registry: PolicyRegistry = JSON.parse(raw);

    const v1_0 = registry.policies.find(p => p.id === 'privacy-v1.0');
    expect(v1_0).toBeDefined();
    expect(v1_0!.status).toBe('SUPERSEDED');
    expect(v1_0!.supersededBy).toBe('privacy-v1.1');
    expect(v1_0!.archivePath).toBe('docs/legal/archive/privacy-v1.0.tsx');
  });
});
