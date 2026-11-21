# Lord Gainsborough Restaurant — Legal Consent Architecture Audit & Review

**Status:** Draft — Pending Business & Legal Review  
**Audit Date:** November 20, 2026  
**Target Release:** Commit `436d600` Post-V6 Release  

---

## 1. Executive Summary

This document records the architectural audit and legal compliance review of the Lord Gainsborough customer consent tracking system, covering backend persistence (Flyway V6), web frontend, and mobile clients.

All legal disclosures and policy versions in this document accurately reflect the running codebase as of the completion of this audit.

---

## 2. Policy Version Enforcement Audit (Item 4)

### 2.1 Current Version Alignment

The three tiers of the Lord Gainsborough application share unified, synchronized policy version identifiers:

| Component | Terms Version | Privacy Version | Status | Effective Date | Source / Implementation |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Backend** | `terms-v1.0` | `privacy-v1.1` | CURRENT | 2026-11-20 | `LegalPolicyService.java` (`CURRENT_PRIVACY_VERSION = "privacy-v1.1"`) |
| **Backend** | — | `privacy-v1.0` | SUPERSEDED | 2026-11-20 | `LegalPolicyService.java` (`PREVIOUS_PRIVACY_VERSION = "privacy-v1.0"`) |
| **Frontend** | `terms-v1.0` | `privacy-v1.1` | CURRENT | 2026-11-20 | `legalConsent.ts`, `signup/page.tsx`, `terms/page.tsx`, `privacy/page.tsx` |
| **Mobile** | `terms-v1.0` | `privacy-v1.1` | CURRENT | 2026-11-20 | `auth/signup.tsx`, `api/recovery.ts` |
| **Database** | `terms-v1.0` | `privacy-v1.0`, `privacy-v1.1` | HISTORICAL + ACTIVE | 2026-11-20 | `account_legal_acceptances` table |

### 2.2 Version Rejection & Enforcement Controls

1. **Initial Signup Dispatch (`/api/auth/signup/send-otp`):**  
   The backend `AuthController.java` verifies that incoming requests contain versions recognized by `LegalPolicyService`. If an outdated version (such as `privacy-v1.0`) is submitted for a new signup, the backend rejects the request immediately with HTTP 400 (`SignupOtpCode.OTP_INVALID`) and an explicit error message prompting the user to review the newer policy version:
   `"A newer version of the Privacy Policy is available. Please review and acknowledge the current version."`
2. **Staged Registration Completion (`/api/auth/signup`):**  
   During OTP completion, `AuthController.java` and `PostgresUserAuthStore.java` check whether the policy version staged in `pending_terms_version` and `pending_privacy_version` is still current. If legal policies were updated between the time the user requested the OTP and completed verification, registration is halted (`Legal policy version has been superseded`) to prevent acceptance of superseded documents.
3. **Database Uniqueness Constraint:**  
   `account_legal_acceptances` contains a composite unique constraint:
   ```sql
   CONSTRAINT uq_legal_acceptances_user_versions 
     UNIQUE (user_id, terms_version, privacy_version, acceptance_source)
   ```
   This prevents duplicate records for identical versions while ensuring that any new version accepted by an existing user creates an immutable, timestamped row.

### 2.3 Policy Content Immutability, Content Archival & Automated CI Enforcement

To guarantee that policy text cannot be silently modified under an existing version identifier, content hashes are recorded for all published texts in an authoritative machine-readable registry (`docs/legal/policy-registry.json` and `backend/src/main/resources/legal/policy-registry.json`):

| Policy Identifier | Type | Status | Archive File | Normalized LF SHA-256 Hash | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `terms-v1.0` | TERMS | CURRENT | `docs/legal/archive/terms-v1.0.tsx` | `5738fec4fe38394d00b786446e084f3369838ff13a150dc4fb783fb712673c7b` | Initial Terms of Service release. |
| `privacy-v1.0` | PRIVACY | SUPERSEDED | `docs/legal/archive/privacy-v1.0.tsx` | `5334d8935908ca78d84f80436f363909f039f1b01a47a1f96bba835a657c6945` | Commit `436d600` release with generic JWT/localStorage references. Preserved for auditability of prior customer consents. |
| `privacy-v1.1` | PRIVACY | CURRENT | `docs/legal/archive/privacy-v1.1.tsx` | `fd2a4f17f70ce0604c1dc1099febf191850e423921c5b85815fc2037782e4f23` | Audited release correcting disclosures to reflect HttpOnly session cookies, BCrypt (strength 12), and cloud hosting. |

#### Automated CI Integrity Verification:
- **Frontend CI Test (`frontend/src/lib/legalPolicyIntegrity.test.ts`):**  
  Reads active source files (`terms/page.tsx`, `privacy/page.tsx`) and archive copies (`docs/legal/archive/*.tsx`), normalizes line endings (`\r\n` -> `\n`), computes SHA-256 hashes, and compares against `docs/legal/policy-registry.json`. If a developer modifies document text without bumping the version identifier, creating an archive copy, and updating the registry, the test fails with an explicit `IMMUTABILITY VIOLATION` error.
- **Backend CI Test (`backend/src/test/java/com/lordgainsborough/restaurant/service/LegalPolicyIntegrityTest.java`):**  
  Verifies that `LegalPolicyService` constants, current versions, and content hashes strictly match the classpath resource `/legal/policy-registry.json`.
- **Historical Auditability Invariant:**  
  Customer records created under `privacy-v1.0` are never retroactively re-labeled or claimed to have accepted revised wording. Their acceptance record points unequivocally to the archived `privacy-v1.0` document hash. All new signups strictly accept `privacy-v1.1`.

### 2.4 Mobile Legal Document Access & Failure Resilience

1. **Reachable Production Origin:**  
   The mobile application targets the live deployed Vercel frontend at `https://lordgainsborough.vercel.app` (configurable via `EXPO_PUBLIC_LEGAL_URL` or `EXPO_PUBLIC_FRONTEND_URL`). Verified via live HTTPS tests with Android and iOS User-Agents, returning HTTP 200 OK with valid trusted TLS certificates:
   - `https://lordgainsborough.vercel.app/terms`
   - `https://lordgainsborough.vercel.app/privacy`
2. **User-Facing Error Presentation:**  
   When document opening cannot proceed (e.g., unsupported URL scheme, missing system browser, or device policy lock), `handleOpenDocument()` sets a clear user-facing error message:  
   `"Unable to open Terms of Service. Please check your browser or network settings."` / `"Unable to open Privacy Policy. Please check your browser or network settings."`  
   Failures are no longer silently swallowed.
3. **Form State Preservation:**  
   Opening legal documents or encountering browser launch failures preserves 100% of the unsent registration fields (`name`, `phone`, `email`, `password`, `confirmPassword`). Verified in `signupConsent.test.tsx`.

---

## 3. Legal Acceptance Retention & Deletion Architecture (Item 5)

### 3.1 Current Architecture: Privacy-First Deletion (`ON DELETE CASCADE`)

In Flyway V6 (`V6__account_legal_acceptances.sql`), the acceptance table foreign key is configured as:
```sql
CONSTRAINT fk_legal_acceptances_user 
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
```

When an account is deleted via `PostgresUserAuthStore.deleteAccountOnce()`, the following operations occur atomically within a PostgreSQL transaction:
1. Active orders check: ensures no `NEW`, `ACCEPTED`, or `READY` orders exist.
2. Complete purge of customer orders (`customer_orders`).
3. Complete purge of table reservations (`reservations`).
4. Complete purge of pending OTP challenges (`signup_otp_challenges`).
5. Purge of the user record (`users`), which cascades through foreign keys to delete:
   - Shopping cart and items (`cart`, `cart_items`)
   - Password reset challenges (`password_reset_otp_challenges`)
   - Legal acceptances (`account_legal_acceptances`)

### 3.2 Evaluation: PIPEDA Data Minimization vs. Defensibility Retention

#### Perspective A: Complete Erasure (Current Implementation)
- **Legal Justification:** Under Canadian federal privacy law (*Personal Information Protection and Electronic Documents Act*, PIPEDA, Principle 4.5.3), personal data must only be retained as long as necessary to fulfill the purposes for which it was collected. When a customer terminates their relationship and deletes their account, retaining identifiable consent metadata without an ongoing relationship risks violating the data minimization mandate.
- **Contractual Disclosure:** Privacy Policy Section 6 explicitly states: *"When an account is deleted through your profile, all personal profile records, order references, and legal acceptance records are permanently removed via cascading deletion..."*

#### Perspective B: Narrowly Justified Legal Retention
- **Legal Justification:** In commercial disputes (e.g., chargeback defense, terms violations, alleged unauthorized transactions), an organization may benefit from demonstrating that the user previously agreed to binding Terms of Service during the statutory limitation period (typically 2 years in Ontario under the *Limitations Act, 2002*).
- **Technical Prerequisite if Adopted:** To reconcile retention with PIPEDA, consent records must NOT contain personal identifiers after account deletion. Instead, the `user_id` foreign key would need to be severed (e.g., `ON DELETE SET NULL`), and records de-identified/pseudonymized with a cryptographic salt, or archived into an immutable audit table that retains only `{acceptance_id, pseudonymous_hash, terms_version, privacy_version, accepted_at, deleted_at}`.

### 3.3 Governance Recommendation

**Decision:** Maintain the current `ON DELETE CASCADE` behavior in V6. Do not modify schema or customer-data deletion behavior without approved legal counsel instruction.

If legal counsel subsequently mandates dispute retention:
1. Do not alter the deployed V6 migration.
2. Introduce a new Flyway migration (`V7__legal_acceptance_retention.sql`).
3. Update Privacy Policy Section 6 to disclose the specific, limited retention period and justification prior to deployment.

---

## 4. Privacy Policy Corrections Audit (Item 1)

The following factual corrections have been verified against backend implementation:

1. **Authentication:** Corrected from `BCrypt/Argon2` to `BCrypt (strength 12)`. Implemented in `PasswordUtil.java`: `new BCryptPasswordEncoder(12)`.
2. **Session Authentication:** Corrected from `JWT in localStorage` to `HttpOnly session cookies`. Implemented in `sessionConfig.ts` (`__Host-lgb_session`) and `auth.ts` (`localStorage.removeItem('authToken')`).
3. **Storage minimization:** Confirmed plaintext passwords, confirmation fields, and OTP codes are strictly barred from `localStorage` and `sessionStorage`.
4. **Cloud hosting & locations:** Removed unverified "enterprise cloud environments", "storage at rest encryption", and specific regional claims ("Canadian and North American data regions") to comply with PIPEDA anti-misrepresentation principles.
5. **Notice of Draft Status:** Maintained prominent banner marking terms as pending business and legal review.
