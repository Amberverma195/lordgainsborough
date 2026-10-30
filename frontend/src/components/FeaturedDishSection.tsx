'use client';

import React, { useState, useEffect } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import styles from '@/app/page.module.css';
import { API_BASE, backendAssetUrl } from '@/lib/orders';

export interface MenuItem {
  id: string;
  name: string;
  description: string;
  price: number;
  category: string;
  image: string;
  available: boolean;
  mostLoved: boolean;
}

const DEFAULT_FEATURED: MenuItem = {
  id: 'default',
  name: 'Signature Wagyu Ribeye',
  description: 'Experience the pinnacle of beef. Our Wagyu Ribeye is aged for 45 days, seared over Japanese Binchotan charcoal, and served with a truffle-infused red wine reduction and smoked sea salt.',
  price: 125,
  category: '',
  image: '/steak.png',
  available: true,
  mostLoved: true
};

export default function FeaturedDishSection() {
  const [items, setItems] = useState<MenuItem[]>([DEFAULT_FEATURED]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isTransitioning, setIsTransitioning] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const fetchMostLoved = async () => {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 4000);
        const res = await fetch(`${API_BASE}/menu/most-loved`, { signal: controller.signal });
        clearTimeout(timeoutId);
        if (!res.ok) return;
        const data = await res.json();
        if (!cancelled && Array.isArray(data) && data.length > 0) {
          const shuffled = [...data].sort(() => 0.5 - Math.random());
          setItems(shuffled);
          setCurrentIndex(0);
        }
      } catch {
        // Fall back gracefully to DEFAULT_FEATURED without breaking rendering or layout
      }
    };

    fetchMostLoved();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (items.length <= 1) return;
    const interval = setInterval(() => {
      setIsTransitioning(true);
      setTimeout(() => {
        setCurrentIndex((prev) => (prev + 1) % items.length);
        setIsTransitioning(false);
      }, 250);
    }, 5000);

    return () => clearInterval(interval);
  }, [items]);

  const currentItem = items[currentIndex] || DEFAULT_FEATURED;
  const imageSrc = currentItem.image && currentItem.image.trim() !== ''
    ? backendAssetUrl(currentItem.image)
    : '/steak.png';

  return (
    <section className={styles.section} style={{ background: 'rgba(255,255,255,0.02)', overflow: 'hidden' }}>
      <div className={`${styles.featuredContainer} ${isTransitioning ? styles.featuredTransitioning : ''}`}>
        <div className={styles.featuredImage}>
          <Image
            src={imageSrc}
            alt={currentItem.name}
            fill
            sizes="(max-width: 768px) 100vw, 50vw"
            style={{ objectFit: 'cover' }}
          />
        </div>
        
        <div className={styles.featuredContent}>
          <p style={{ color: 'var(--secondary)', letterSpacing: '0.2em', textTransform: 'uppercase' }}>Most Loved</p>
          <h3>{currentItem.name}</h3>
          <p>{currentItem.description}</p>
          <span className={styles.priceTag}>${currentItem.price.toFixed(2)}</span>
          <Link href="/menu" className={styles.primaryCta} style={{ width: 'fit-content' }}>
            View Full Menu
          </Link>
        </div>
      </div>
    </section>
  );
}
