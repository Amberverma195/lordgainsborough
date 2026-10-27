import Link from 'next/link';
import { RESTAURANT_PHONE_DISPLAY, RESTAURANT_PHONE_TEL } from '@/lib/phone';

export default function ContactPage() {
  return <section style={{ maxWidth: 900, margin: '3rem auto', padding: '1.5rem' }}>
    <h1>Contact Lord Gainsborough</h1>
    <p>1800 Hyde Park Rd, London, ON N6H 0K1</p>
    <p><a href={`tel:${RESTAURANT_PHONE_TEL}`}>{RESTAURANT_PHONE_DISPLAY}</a></p>
    <p><Link href="/reserve">Book a Table</Link> · <Link href="/menu">Explore Menu</Link></p>
  </section>;
}
