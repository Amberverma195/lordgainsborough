import Link from 'next/link';

export default function DeleteAccountPage() {
  return (
    <main style={{ maxWidth: 760, margin: '120px auto', padding: 24 }}>
      <h1>Delete your Lord Gainsborough account</h1>
      <p>
        You can permanently delete your account in the customer app under
        Profile, then Delete account, or on this website:
      </p>
      <ol>
        <li>
          <Link href="/login">Sign in to your account.</Link>
        </li>
        <li>
          <Link href="/profile">Open Profile</Link> and choose Delete account.
        </li>
        <li>Enter your current password and confirm permanent deletion.</li>
      </ol>
      <p>
        Complete or resolve active orders first. Deletion removes your account
        and associated personal data and revokes access. This cannot be undone.
        Records that must be retained by law are handled according to our{' '}
        <Link href="/privacy">Privacy Policy</Link>.
      </p>
      <p>
        If you cannot access your account, contact{' '}
        <a href="mailto:dine@lordgainsborough.com">dine@lordgainsborough.com</a>{' '}
        for help. Do not email your password.
      </p>
    </main>
  );
}
