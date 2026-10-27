import Link from 'next/link';

export default function AboutPage() {
  return <section style={{ maxWidth: 900, margin: '3rem auto', padding: '1.5rem' }}>
    <h1>About Lord Gainsborough</h1>
    <p>Discover our restaurant at 1800 Hyde Park Rd in London, Ontario.</p>
    <p>Browse our menu, order for pickup, or book a table for your next visit.</p>
    <p><Link href="/menu">Explore Menu</Link> · <Link href="/reserve">Book a Table</Link> · <Link href="/contact">Contact us</Link></p>
  </section>;
}
