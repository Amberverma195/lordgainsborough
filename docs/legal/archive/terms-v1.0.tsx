import React from 'react';
import Link from 'next/link';
import type { Metadata } from 'next';
import { ArrowLeft, Shield } from 'lucide-react';
import styles from '../legal.module.css';

export const metadata: Metadata = {
  title: 'Terms of Service | Lord Gainsborough Restaurant',
  description: 'Terms of Service governing customer accounts, online ordering, reservations, and use of Lord Gainsborough Restaurant services.',
};

export default function TermsOfServicePage() {
  return (
    <main className={styles.legalContainer}>
      <article className={styles.legalContent}>
        <header className={styles.header}>
          <h1 className={styles.title}>Terms of Service</h1>
          <div className={styles.metaRow}>
            <span className={styles.versionBadge}>
              <Shield size={14} /> Version: terms-v1.0
            </span>
            <span>Effective Date: November 20, 2026</span>
            <span>Last Updated: November 20, 2026</span>
          </div>
        </header>

        <aside className={styles.draftNotice} role="alert">
          <strong>Draft Notice - Pending Business & Legal Review</strong>
          This document represents the proposed Terms of Service for Lord Gainsborough Restaurant and is currently marked as a preliminary draft awaiting final business and legal review. The operational terms below accurately reflect the current technical architecture and customer policies implemented in the website and ordering systems.
        </aside>

        <section className={styles.section}>
          <h2>1. Agreement to Terms</h2>
          <p>
            These Terms of Service (&ldquo;Terms&rdquo;) constitute a legally binding agreement between you (&ldquo;Customer&rdquo;, &ldquo;you&rdquo;, or &ldquo;your&rdquo;) and Lord Gainsborough Restaurant (&ldquo;Lord Gainsborough&rdquo;, &ldquo;we&rdquo;, &ldquo;us&rdquo;, or &ldquo;our&rdquo;), located at 1800 Hyde Park Rd, London, Ontario, Canada.
          </p>
          <p>
            By accessing or using our website, creating a customer account, placing pickup orders, or booking table reservations, you agree to be bound by these Terms and acknowledge that you have read our Privacy Policy. If you do not agree to these Terms, you must not create an account or use our online services.
          </p>
        </section>

        <section className={styles.section}>
          <h2>2. Eligibility & Account Registration</h2>
          <p>
            To create an account and place orders through our platform, you must:
          </p>
          <ul>
            <li>Be at least the age of majority in Ontario (18 years of age) or have parental/guardian consent.</li>
            <li>Provide accurate, current, and complete registration information, including your legal name, a valid email address, and a 10-digit telephone number.</li>
            <li>Verify your email address using the one-time password (OTP) verification code dispatched during registration.</li>
            <li>Maintain the confidentiality of your credentials. You are responsible for all activities that occur under your account.</li>
          </ul>
          <p>
            You agree to notify us immediately of any unauthorized use of your account or any other breach of security. Lord Gainsborough reserves the right to suspend or terminate accounts that contain false or deceptive information.
          </p>
        </section>

        <section className={styles.section}>
          <h2>3. Online Ordering, Pickup & Payment</h2>
          <p>
            Our online platform facilitates takeout and pickup orders from our London, Ontario location:
          </p>
          <ul>
            <li><strong>Pricing & Currency:</strong> All prices displayed on our website are in Canadian Dollars (CAD) and are subject to applicable provincial and federal taxes (HST), which are calculated at checkout.</li>
            <li><strong>Pickup Procedures:</strong> Orders are prepared for in-store pickup at 1800 Hyde Park Rd. You are responsible for picking up your order during the scheduled pickup window.</li>
            <li><strong>Order Confirmation:</strong> An order is only confirmed when accepted by our kitchen display and ordering system. You will receive real-time status updates through your customer dashboard.</li>
            <li><strong>Modifications and Cancellations:</strong> Because our kitchen prepares fresh food promptly upon order receipt, cancellations or modifications are permitted only prior to order acceptance by the kitchen.</li>
            <li><strong>No-Show Policy:</strong> If an order is prepared and not collected within the designated pickup window, the order cannot be refunded. Customers who accumulate repeated uncollected orders may have pickup privileges restricted on their account.</li>
          </ul>
        </section>

        <section className={styles.section}>
          <h2>4. Table Reservations</h2>
          <p>
            Table reservations booked through our platform are subject to restaurant availability and dining room capacity. We kindly request that guests arrive promptly for their reservation. Tables are held for a maximum of 15 minutes past the reserved time before being released to waiting guests. Please notify us at least two hours in advance if you need to cancel or modify your reservation party size.
          </p>
        </section>

        <section className={styles.section}>
          <h2>5. Allergies & Food Dietary Disclaimers</h2>
          <p>
            While our culinary team exercises great care in preparing all dishes, our kitchen handles common allergens including dairy, nuts, gluten, eggs, soy, shellfish, and fish. Cross-contact may occur. If you or any member of your party has a food allergy or dietary requirement, please indicate this in the order notes and contact restaurant staff directly prior to consumption.
          </p>
        </section>

        <section className={styles.section}>
          <h2>6. User Conduct & Security</h2>
          <p>
            You agree not to engage in any activity that interferes with or disrupts the security, performance, or availability of our services. Prohibited conduct includes:
          </p>
          <ul>
            <li>Attempting to bypass rate limiting, OTP verification, or authentication controls.</li>
            <li>Submitting automated, scripted, or abusive requests to our API endpoints or servers.</li>
            <li>Using disposable, fraudulent, or unowned email addresses or telephone numbers.</li>
            <li>Interfering with other customers&rsquo; access to ordering or reservations.</li>
          </ul>
          <p>
            Violations of system security may result in immediate account termination, cancellation of pending orders, and reporting to law enforcement authorities.
          </p>
        </section>

        <section className={styles.section}>
          <h2>7. Account Termination & Deletion</h2>
          <p>
            You may request deletion of your customer account at any time through your account profile page or by contacting our team. Account deletion will permanently erase your personal profile and authentication credentials, provided you have no active, unfulfilled orders.
          </p>
          <p>
            Lord Gainsborough reserves the right to suspend or terminate accounts that breach these Terms, engage in payment chargeback fraud, or abuse restaurant staff or services.
          </p>
        </section>

        <section className={styles.section}>
          <h2>8. Intellectual Property</h2>
          <p>
            All trademarks, logos, menu copy, food photography, graphics, and software underlying the Lord Gainsborough website and mobile applications are the property of Lord Gainsborough Restaurant or its licensors. You may not reproduce, distribute, or create derivative works from our content without prior written permission.
          </p>
        </section>

        <section className={styles.section}>
          <h2>9. Limitation of Liability & Disclaimers</h2>
          <p>
            To the maximum extent permitted by applicable law in Ontario, our services, website, and digital ordering platforms are provided on an &ldquo;as is&rdquo; and &ldquo;as available&rdquo; basis without warranties of any kind. Lord Gainsborough will not be liable for any indirect, incidental, special, consequential, or punitive damages arising from the use of or inability to use our digital services.
          </p>
        </section>

        <section className={styles.section}>
          <h2>10. Governing Law</h2>
          <p>
            These Terms are governed by and construed in accordance with the laws of the Province of Ontario and the federal laws of Canada applicable therein, without regard to conflict of law principles. Any dispute arising under these Terms shall be subject to the exclusive jurisdiction of the courts of Ontario.
          </p>
        </section>

        <section className={styles.section}>
          <h2>11. Contact Information</h2>
          <p>
            If you have questions regarding these Terms of Service or our restaurant policies, please contact us:
          </p>
          <div className={styles.contactBox}>
            <p><strong>Lord Gainsborough Restaurant</strong></p>
            <p>1800 Hyde Park Rd, London, ON N6H 0K1, Canada</p>
            <p>Phone: <a href="tel:+15194732362">+1 (519)-473-2362</a></p>
            <p>Email: <a href="mailto:legal@lordgainsborough.com">legal@lordgainsborough.com</a></p>
            <p>General Inquiries: <a href="mailto:dine@lordgainsborough.com">dine@lordgainsborough.com</a></p>
          </div>
        </section>

        <nav className={styles.footerNav} aria-label="Legal document navigation">
          <Link href="/signup" className={styles.backLink}>
            <ArrowLeft size={16} /> Return to Signup
          </Link>
          <div className={styles.navLinks}>
            <Link href="/privacy">Privacy Policy</Link>
            <Link href="/">Home</Link>
          </div>
        </nav>
      </article>
    </main>
  );
}
