import Link from 'next/link';
import styles from './404/not-found.module.css';

export default function NotFound() {
  return (
    <div className={styles.container}>
      <h1 className={styles.code}>404</h1>
      <h2 className={styles.title}>Page Not Found</h2>
      <p className={styles.message}>
        The page you&apos;re looking for doesn&apos;t exist or you don&apos;t have permission to view it.
      </p>
      <Link href="/" className={styles.homeBtn}>
        Return Home
      </Link>
    </div>
  );
}
