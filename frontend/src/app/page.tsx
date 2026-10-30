'use client';

import React, { useRef, useState, useEffect } from 'react';
import Image from "next/image";
import Link from "next/link";
import styles from "./page.module.css";
import { motion, AnimatePresence, useScroll, useTransform } from "framer-motion";
import { Utensils, Clock, MapPin, ChevronRight, Star, Coffee } from "lucide-react";
import { API_BASE, backendAssetUrl } from "@/lib/orders";

interface MenuItem {
  id: string;
  name: string;
  description: string;
  price: number;
  category: string;
  image: string;
  available: boolean;
  mostLoved: boolean;
}

export default function Home() {
  const containerRef = useRef<HTMLDivElement>(null);
  const { scrollY } = useScroll();
  
  const [mostLovedItems, setMostLovedItems] = useState<MenuItem[]>([]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isMobileOrReducedMotion, setIsMobileOrReducedMotion] = useState(false);

  useEffect(() => {
    const updateMotionPreference = () => {
      const isMobile = window.innerWidth <= 768;
      const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      setIsMobileOrReducedMotion(isMobile || prefersReduced);
    };

    updateMotionPreference();
    window.addEventListener('resize', updateMotionPreference);
    const mediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    mediaQuery.addEventListener?.('change', updateMotionPreference);

    return () => {
      window.removeEventListener('resize', updateMotionPreference);
      mediaQuery.removeEventListener?.('change', updateMotionPreference);
    };
  }, []);

  useEffect(() => {
    const fetchMostLoved = async () => {
      try {
        const res = await fetch(`${API_BASE}/menu/most-loved`);
        const data = await res.json();
        if (data.length > 0) {
          const shuffled = data.sort(() => 0.5 - Math.random());
          setMostLovedItems(shuffled);
        }
      } catch {
        setMostLovedItems([]);
      }
    };
    fetchMostLoved();
  }, []);

  useEffect(() => {
    if (mostLovedItems.length <= 1) return;
    const interval = setInterval(() => {
      setCurrentIndex((prev) => (prev + 1) % mostLovedItems.length);
    }, 5000);
    return () => clearInterval(interval);
  }, [mostLovedItems]);

  // Desktop Parallax effects (disabled on mobile / reduced-motion to ensure rock-solid stability)
  const heroY = useTransform(scrollY, [0, 1000], [0, 320]);
  const heroOpacity = useTransform(scrollY, [0, 500], [1, 0]);
  const heroScale = useTransform(scrollY, [0, 500], [1, 1.08]);

  // Default featured item if none are set in admin
  const defaultFeatured = {
    id: 'default',
    name: 'Signature Wagyu Ribeye',
    description: 'Experience the pinnacle of beef. Our Wagyu Ribeye is aged for 45 days, seared over Japanese Binchotan charcoal, and served with a truffle-infused red wine reduction and smoked sea salt.',
    price: 125,
    category: '',
    image: '/steak.png',
    available: true,
    mostLoved: true
  };

  const currentItem = mostLovedItems.length > 0 ? mostLovedItems[currentIndex] : defaultFeatured;

  return (
    <div className={styles.page} ref={containerRef}>
      {/* Background Orbs contained to guarantee zero horizontal document overflow */}
      <div className={styles.decorationsContainer} aria-hidden="true">
        <motion.div 
          className={`${styles.decoration} ${styles.decorationLeft}`}
          animate={isMobileOrReducedMotion ? undefined : { 
            scale: [1, 1.15, 1],
            opacity: [0.1, 0.18, 0.1]
          }}
          transition={{ duration: 10, repeat: Infinity }}
        >
          <div className={styles.floatingOrb} />
        </motion.div>
        
        <motion.div 
          className={`${styles.decoration} ${styles.decorationRight}`}
          animate={isMobileOrReducedMotion ? undefined : { 
            scale: [1.15, 1, 1.15],
            opacity: [0.1, 0.18, 0.1]
          }}
          transition={{ duration: 12, repeat: Infinity }}
        >
          <div className={styles.floatingOrb} />
        </motion.div>
      </div>

      {/* Hero Section */}
      <section className={styles.hero}>
        <motion.div 
          className={styles.heroImageWrapper}
          style={isMobileOrReducedMotion ? undefined : { y: heroY, scale: heroScale }}
        >
          <Image
            src="/restaurant-interior.png"
            alt="Lord Gainsborough Interior"
            fill
            sizes="100vw"
            className={styles.heroImage}
            priority
          />
          <div className={styles.heroOverlay} />
        </motion.div>

        <motion.div 
          className={styles.heroContent}
          style={isMobileOrReducedMotion ? undefined : { opacity: heroOpacity }}
        >
          <motion.div
            initial={{ opacity: 0, y: 25 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.8, ease: "easeOut" }}
          >
            <span className={styles.heroEyebrow}>
              Established 1985
            </span>
            <h1 className={styles.heroTitle}>
              Lord <br /> Gainsborough
            </h1>
          </motion.div>

          <motion.p 
            className={styles.heroSubtitle}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.8, delay: 0.2, ease: "easeOut" }}
          >
            Mastery of Taste &amp; Timeless Elegance
          </motion.p>

          <motion.div
            className={styles.heroActions}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.8, delay: 0.4, ease: "easeOut" }}
          >
            <Link href="/menu" className={styles.primaryCta}>
              Explore Menu <ChevronRight size={18} />
            </Link>
            <Link href="/reserve" className={styles.cta}>
              Book a Table
            </Link>
          </motion.div>
        </motion.div>

        {/* Scroll Indicator */}
        <motion.div 
          className={styles.scrollIndicator}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 1, duration: 1 }}
          style={{ position: 'absolute', bottom: '2rem', left: '50%', transform: 'translateX(-50%)', zIndex: 10 }}
        >
          <motion.div 
            animate={isMobileOrReducedMotion ? undefined : { y: [0, 10, 0] }}
            transition={{ duration: 2, repeat: Infinity }}
            style={{ width: '1px', height: '60px', background: 'linear-gradient(to bottom, var(--secondary), transparent)' }}
          />
        </motion.div>
      </section>

      {/* Philosophy Section - Two Balanced & Centered Cards */}
      <section className={styles.section}>
        <div className={styles.sectionTitle}>
          <motion.p
            initial={{ opacity: 0, y: 10 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
          >
            The Art of Dining
          </motion.p>
          <motion.h2
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ delay: 0.2 }}
          >
            A Tradition of Excellence
          </motion.h2>
        </div>

        <div className={styles.philosophyGrid}>
          {[
            { icon: Utensils, title: "Gourmet Cuisine", text: "Masterfully crafted dishes using heritage recipes and local seasonal harvests." },
            { icon: Star, title: "Unrivaled Service", text: "Attentive, discreet, and personal service that anticipates your every need." }
          ].map((item, i) => (
            <motion.div 
              key={i}
              className={`${styles.card} premium-card`}
              initial={{ opacity: 0, y: 30 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true }}
              transition={{ delay: i * 0.2 }}
            >
              <item.icon size={40} color="var(--secondary)" style={{ marginBottom: '1.5rem' }} />
              <h3>{item.title}</h3>
              <p>{item.text}</p>
            </motion.div>
          ))}
        </div>
      </section>

      {/* Featured Dish Section */}
      <section className={styles.section} style={{ background: 'rgba(255,255,255,0.02)', overflow: 'hidden' }}>
        <AnimatePresence mode="wait">
          <motion.div 
            key={currentItem.id}
            className={styles.featuredContainer}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -20 }}
            transition={{ duration: 0.8 }}
          >
            <div className={styles.featuredImage}>
              <Image
                src={
                  currentItem.image && currentItem.image !== ""
                    ? backendAssetUrl(currentItem.image)
                    : '/steak.png'
                }
                alt={currentItem.name}
                fill
                sizes="(max-width: 768px) 100vw, 50vw"
                style={{ objectFit: 'cover' }}
              />
            </div>
            
            <div className={styles.featuredContent}>
              <p style={{ color: 'var(--secondary)', letterSpacing: '0.2em', textTransform: 'uppercase' }}>Most Loved</p>
              <h3>{currentItem.name}</h3>
              <p>
                {currentItem.description}
              </p>
              <span className={styles.priceTag}>${currentItem.price.toFixed(2)}</span>
              <Link href="/menu" className={styles.primaryCta} style={{ width: 'fit-content' }}>
                View Full Menu
              </Link>
            </div>
          </motion.div>
        </AnimatePresence>
      </section>

      {/* Information Grid */}
      <section className={styles.section}>
        <div className={styles.grid}>
          <motion.div 
            className={`${styles.card} premium-card`}
            initial={{ opacity: 0, scale: 0.95 }}
            whileInView={{ opacity: 1, scale: 1 }}
            viewport={{ once: true }}
          >
            <Clock size={32} color="var(--secondary)" style={{ marginBottom: '1rem' }} />
            <h3>Hours</h3>
            <div style={{ color: 'var(--text-light)', lineHeight: '2' }}>
              Mon - Thu: 9:00 AM - 10:00 PM<br />
              Fri - Sat: 9:00 AM - 11:30 PM<br />
              Sun: 10:00 AM - 9:00 PM
            </div>
          </motion.div>

          <motion.div 
            className={`${styles.card} premium-card`}
            initial={{ opacity: 0, scale: 0.95 }}
            whileInView={{ opacity: 1, scale: 1 }}
            viewport={{ once: true }}
            transition={{ delay: 0.2 }}
          >
            <MapPin size={32} color="var(--secondary)" style={{ marginBottom: '1rem' }} />
            <h3>Address</h3>
            <p>1800 Hyde Park Rd<br />London, ON N6H 0K1</p>
            <a href="#" style={{ color: 'var(--secondary)', marginTop: '1rem', display: 'block', fontSize: '0.9rem' }}>Get Directions</a>
          </motion.div>

          <motion.div 
            className={`${styles.card} premium-card`}
            initial={{ opacity: 0, scale: 0.95 }}
            whileInView={{ opacity: 1, scale: 1 }}
            viewport={{ once: true }}
            transition={{ delay: 0.4 }}
          >
            <Coffee size={32} color="var(--secondary)" style={{ marginBottom: '1rem' }} />
            <h3>Private Dining</h3>
            <p>Exclusive rooms for intimate gatherings and corporate events up to 50 guests.</p>
            <Link href="/reserve" style={{ color: 'var(--secondary)', marginTop: '1rem', display: 'block', fontSize: '0.9rem' }}>Inquire Now</Link>
          </motion.div>
        </div>
      </section>
    </div>
  );
}
