'use client';

import React, { useState, useEffect } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import styles from '@/app/page.module.css';
import { backendAssetUrl } from '@/lib/orders';
import { fetchFeaturedDishes, MenuItem } from '@/lib/featuredDish';

export type { MenuItem };

export default function FeaturedDishSection() {
  const [items, setItems] = useState<MenuItem[]>([]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isTransitioning, setIsTransitioning] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchFeaturedDishes().then((dishes) => {
      if (!cancelled && dishes.length > 0) {
        const shuffled = dishes.length > 1 ? [...dishes].sort(() => 0.5 - Math.random()) : dishes;
        setItems(shuffled);
        setCurrentIndex(0);
      }
    });

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

  if (items.length === 0) {
    return null;
  }

  const currentItem = items[currentIndex];
  if (!currentItem) {
    return null;
  }

  const imageSrc = currentItem.image && currentItem.image.trim() !== ''
    ? backendAssetUrl(currentItem.image)
    : '/restaurant-interior.png';

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
