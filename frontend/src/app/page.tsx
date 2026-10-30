import React from 'react';
import Link from 'next/link';
import styles from './page.module.css';
import { Utensils, Clock, MapPin, ChevronRight, Star, Coffee } from 'lucide-react';
import HeroBackground from '@/components/HeroBackground';
import FeaturedDishSection from '@/components/FeaturedDishSection';

export default function Home() {
  return (
    <div className={styles.page}>
      {/* Background Orbs contained to guarantee zero horizontal document overflow */}
      <div className={styles.decorationsContainer} aria-hidden="true">
        <div className={`${styles.decoration} ${styles.decorationLeft}`}>
          <div className={styles.floatingOrb} />
        </div>
        <div className={`${styles.decoration} ${styles.decorationRight}`}>
          <div className={styles.floatingOrb} />
        </div>
      </div>

      {/* Hero Section - Instantly readable SSR HTML */}
      <section className={styles.hero}>
        <HeroBackground />

        <div className={styles.heroContent}>
          <div>
            <span className={styles.heroEyebrow}>Established 1985</span>
            <h1 className={styles.heroTitle}>
              Lord <br /> Gainsborough
            </h1>
          </div>

          <p className={styles.heroSubtitle}>
            Mastery of Taste &amp; Timeless Elegance
          </p>

          <div className={styles.heroActions}>
            <Link href="/menu" className={styles.primaryCta}>
              Explore Menu <ChevronRight size={18} />
            </Link>
            <Link href="/reserve" className={styles.cta}>
              Book a Table
            </Link>
          </div>
        </div>

        {/* Scroll Indicator */}
        <div className={styles.scrollIndicator} aria-hidden="true">
          <div className={styles.scrollLine} />
        </div>
      </section>

      {/* Philosophy Section - Two Balanced & Centered Cards */}
      <section className={styles.section}>
        <div className={styles.sectionTitle}>
          <p>The Art of Dining</p>
          <h2>A Tradition of Excellence</h2>
        </div>

        <div className={styles.philosophyGrid}>
          <div className={`${styles.card} premium-card`}>
            <Utensils size={40} color="var(--secondary)" style={{ marginBottom: '1.5rem' }} />
            <h3>Gourmet Cuisine</h3>
            <p>Masterfully crafted dishes using heritage recipes and local seasonal harvests.</p>
          </div>

          <div className={`${styles.card} premium-card`}>
            <Star size={40} color="var(--secondary)" style={{ marginBottom: '1.5rem' }} />
            <h3>Unrivaled Service</h3>
            <p>Attentive, discreet, and personal service that anticipates your every need.</p>
          </div>
        </div>
      </section>

      {/* Featured Dish Section (Isolated client component with instant SSR default item) */}
      <FeaturedDishSection />

      {/* Information Grid */}
      <section className={styles.section}>
        <div className={styles.grid}>
          <div className={`${styles.card} premium-card`}>
            <Clock size={32} color="var(--secondary)" style={{ marginBottom: '1rem' }} />
            <h3>Hours</h3>
            <div style={{ color: 'var(--text-light)', lineHeight: '2' }}>
              Mon - Thu: 9:00 AM - 10:00 PM<br />
              Fri - Sat: 9:00 AM - 11:30 PM<br />
              Sun: 10:00 AM - 9:00 PM
            </div>
          </div>

          <div className={`${styles.card} premium-card`}>
            <MapPin size={32} color="var(--secondary)" style={{ marginBottom: '1rem' }} />
            <h3>Address</h3>
            <p>1800 Hyde Park Rd<br />London, ON N6H 0K1</p>
            <a
              href="https://maps.google.com/?q=1800+Hyde+Park+Rd,+London,+ON+N6H+0K1"
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: 'var(--secondary)', marginTop: '1rem', display: 'block', fontSize: '0.9rem' }}
            >
              Get Directions
            </a>
          </div>

          <div className={`${styles.card} premium-card`}>
            <Coffee size={32} color="var(--secondary)" style={{ marginBottom: '1rem' }} />
            <h3>Private Dining</h3>
            <p>Exclusive rooms for intimate gatherings and corporate events up to 50 guests.</p>
            <Link href="/reserve" style={{ color: 'var(--secondary)', marginTop: '1rem', display: 'block', fontSize: '0.9rem' }}>
              Inquire Now
            </Link>
          </div>
        </div>
      </section>
    </div>
  );
}
