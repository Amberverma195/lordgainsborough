import React from 'react';
import Link from 'next/link';
import type { Metadata } from 'next';
import { ArrowLeft, Lock } from 'lucide-react';
import styles from '../legal.module.css';

export const metadata: Metadata = {
  title: 'Privacy Policy | Lord Gainsborough Restaurant',
  description: 'Privacy Policy describing how Lord Gainsborough Restaurant collects, uses, protects, and retains customer information.',
};

export default function PrivacyPolicyPage() {
  return (
    <main className={styles.legalContainer}>
      <article className={styles.legalContent}>
        <header className={styles.header}>
          <h1 className={styles.title}>Privacy Policy</h1>
          <div className={styles.metaRow}>
            <span className={styles.versionBadge}>
              <Lock size={14} /> Version: privacy-v1.0
            </span>
            <span>Effective Date: November 20, 2026</span>
            <span>Last Updated: November 20, 2026</span>
          </div>
        </header>

        <aside className={styles.draftNotice} role="alert">
          <strong>Draft Notice - Pending Business & Legal Review</strong>
          This document represents the proposed Privacy Policy for Lord Gainsborough Restaurant and is currently marked as a preliminary draft awaiting final business and legal review. The disclosures below accurately describe the data collection, authentication, storage, and retention practices implemented in the application codebase and database architecture.
        </aside>

        <section className={styles.section}>
          <h2>1. Introduction</h2>
          <p>
            Lord Gainsborough Restaurant (&ldquo;Lord Gainsborough&rdquo;, &ldquo;we&rdquo;, &ldquo;us&rdquo;, or &ldquo;our&rdquo;) is committed to protecting the privacy and personal information of our dining guests and online customers. This Privacy Policy explains how we collect, use, store, and safeguard your personal information when you visit our website, register an account, order pickup meals, or reserve a dining table.
          </p>
          <p>
            Our restaurant operates from 1800 Hyde Park Rd, London, Ontario, Canada, and our data handling practices comply with the <em>Personal Information Protection and Electronic Documents Act</em> (PIPEDA) of Canada and applicable Ontario privacy laws.
          </p>
        </section>

        <section className={styles.section}>
          <h2>2. What Information We Collect</h2>
          <p>
            We collect only the personal information necessary to deliver our restaurant services, fulfill food orders, and manage secure accounts:
          </p>
          <ul>
            <li>
              <strong>Account & Profile Data:</strong> Full name, email address, and 10-digit telephone number (stored in both raw formatted and normalized digits for accurate account retrieval).
            </li>
            <li>
              <strong>Authentication Information:</strong> Passwords are cryptographically salted and hashed using modern algorithms (BCrypt/Argon2) before database insertion. Plaintext passwords are never persisted in the database, stored in browser storage, or recorded in server logs. One-time password (OTP) verification codes are hashed with BCrypt and expire after 10 minutes.
            </li>
            <li>
              <strong>Order & Transaction Records:</strong> Menu selections, customized preparation instructions, order status progression, order timestamps, subtotal, tax, total cost, and payment receipt reference tokens.
            </li>
            <li>
              <strong>Table Reservation Records:</strong> Guest name, contact telephone number, reservation date and time, party size, and special seating requests.
            </li>
            <li>
              <strong>Legal Consent Evidence:</strong> Append-only records of your agreement to the Terms of Service and acknowledgement of this Privacy Policy, including your internal account identifier, policy version identifiers (e.g. <code>terms-v1.0</code>, <code>privacy-v1.0</code>), authoritative server timestamp, and acceptance source (e.g. <code>signup</code>).
            </li>
            <li>
              <strong>Technical & Abuse Prevention Data:</strong> Truncated/hashed client IP addresses for rate limiting and fraud protection against denial-of-service and credential stuffing attacks. Raw IP addresses are used transiently during the request lifecycle and are not stored in customer consent records.
            </li>
          </ul>
        </section>

        <section className={styles.section}>
          <h2>3. How We Use Your Information</h2>
          <p>
            We use your personal information strictly for legitimate restaurant operations:
          </p>
          <ul>
            <li>To verify your identity and authenticate account logins using secure JSON Web Tokens (JWT).</li>
            <li>To process, prepare, and notify you regarding the status of your takeout pickup orders.</li>
            <li>To reserve and manage your table seating in our dining room.</li>
            <li>To send critical, non-marketing operational communications, such as email verification OTPs and order status updates.</li>
            <li>To detect, investigate, and prevent fraudulent orders, abuse of pickup privileges, and security breaches.</li>
            <li>To maintain auditable records of legal acceptance as required by law.</li>
          </ul>
        </section>

        <section className={styles.section}>
          <h2>4. Cookies & Browser Storage</h2>
          <p>
            We practice strict data minimization regarding browser storage:
          </p>
          <ul>
            <li><strong>Authentication Tokens:</strong> Secure JWT bearer tokens are stored in browser local storage solely to keep you authenticated during active sessions.</li>
            <li><strong>Shopping Cart:</strong> Non-sensitive pending cart selections are stored in component memory and local storage to prevent loss of your order before checkout.</li>
            <li><strong>Strict Invariant:</strong> Plaintext passwords, password confirmation fields, and one-time verification codes are never stored in <code>localStorage</code>, <code>sessionStorage</code>, cookies, or URL parameters. Passwords remain only in transient memory during form completion.</li>
            <li><strong>No Tracking Cookies:</strong> We do not deploy third-party advertising cookies, behavioral tracking beacons, or cross-site tracking scripts.</li>
          </ul>
        </section>

        <section className={styles.section}>
          <h2>5. Third-Party Service Providers</h2>
          <p>
            We do not sell, rent, or trade your personal information. We share minimal data only with trusted infrastructure providers required to operate our digital systems:
          </p>
          <ul>
            <li><strong>Transactional Email Delivery:</strong> We use <em>Resend</em> to deliver one-time verification passwords and account notifications. Resend processes recipient email addresses and delivery outcomes under strict data protection terms.</li>
            <li><strong>Cloud Hosting & PostgreSQL Database:</strong> Our application servers and relational PostgreSQL databases are hosted in enterprise cloud environments with encrypted storage at rest and in transit.</li>
          </ul>
        </section>

        <section className={styles.section}>
          <h2>6. Data Storage, Retention & Deletion</h2>
          <p>
            Your information is stored in secured PostgreSQL databases located in Canadian and North American cloud data regions.
          </p>
          <ul>
            <li><strong>Active Account Data:</strong> Retained for the duration of your active account membership to enable order history review and loyalty benefits.</li>
            <li><strong>Account Deletion:</strong> You have the right to delete your account at any time. When an account is deleted through your profile, all personal profile records, order references, and legal acceptance records are permanently removed via cascading deletion, provided there are no active, uncompleted orders.</li>
            <li><strong>Security Logs:</strong> Transient abuse-prevention counters and rate-limit states expire automatically within hours or days.</li>
          </ul>
        </section>

        <section className={styles.section}>
          <h2>7. Security Safeguards</h2>
          <p>
            We implement technical, administrative, and physical safeguards designed to protect your personal information against unauthorized access, loss, or alteration:
          </p>
          <ul>
            <li>All communications between your browser or mobile device and our servers are encrypted using modern Transport Layer Security (TLS 1.2 and TLS 1.3).</li>
            <li>Passwords are secured with salted, iterative cryptographic hashes (BCrypt) before database entry.</li>
            <li>OTP verification enforces strict 10-minute expiration, 60-second resend cooldowns, and locks challenges after 5 failed attempts.</li>
            <li>Database transactions ensure atomic account creation and legal acceptance recording, guaranteeing no orphaned records.</li>
          </ul>
        </section>

        <section className={styles.section}>
          <h2>8. Your Privacy Rights</h2>
          <p>
            Under Canadian privacy legislation, you have the right to:
          </p>
          <ul>
            <li>Access the personal information we hold about you by viewing your customer profile and order history.</li>
            <li>Request corrections to any inaccurate or incomplete personal information.</li>
            <li>Request the deletion of your account and personal data.</li>
            <li>Withdraw consent to ongoing data processing by closing your account.</li>
          </ul>
        </section>

        <section className={styles.section}>
          <h2>9. Contact Us</h2>
          <p>
            For privacy inquiries, access requests, or questions regarding our data practices, please contact our Privacy Officer:
          </p>
          <div className={styles.contactBox}>
            <p><strong>Privacy Officer — Lord Gainsborough Restaurant</strong></p>
            <p>1800 Hyde Park Rd, London, ON N6H 0K1, Canada</p>
            <p>Email: <a href="mailto:privacy@lordgainsborough.com">privacy@lordgainsborough.com</a></p>
            <p>Phone: <a href="tel:+15194732362">+1 (519)-473-2362</a></p>
          </div>
        </section>

        <nav className={styles.footerNav} aria-label="Legal document navigation">
          <Link href="/signup" className={styles.backLink}>
            <ArrowLeft size={16} /> Return to Signup
          </Link>
          <div className={styles.navLinks}>
            <Link href="/terms">Terms of Service</Link>
            <Link href="/">Home</Link>
          </div>
        </nav>
      </article>
    </main>
  );
}
