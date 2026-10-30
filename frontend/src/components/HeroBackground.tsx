'use client';

import React, { useEffect, useState } from 'react';
import Image from 'next/image';
import styles from '@/app/page.module.css';

export default function HeroBackground() {
  const [scrollY, setScrollY] = useState(0);
  const [enableParallax, setEnableParallax] = useState(false);

  useEffect(() => {
    // Only enable subtle parallax on desktop devices without reduced motion preference
    const checkParallax = () => {
      const isDesktop = window.innerWidth > 768;
      const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      setEnableParallax(isDesktop && !prefersReduced);
    };

    checkParallax();
    window.addEventListener('resize', checkParallax, { passive: true });

    let rafId: number | null = null;
    const handleScroll = () => {
      if (rafId !== null) return;
      rafId = window.requestAnimationFrame(() => {
        setScrollY(window.scrollY);
        rafId = null;
      });
    };

    window.addEventListener('scroll', handleScroll, { passive: true });

    return () => {
      window.removeEventListener('resize', checkParallax);
      window.removeEventListener('scroll', handleScroll);
      if (rafId !== null) {
        cancelAnimationFrame(rafId);
      }
    };
  }, []);

  // Desktop subtle transform capped to prevent extreme offsets
  const transformStyle = enableParallax && scrollY > 0
    ? {
        transform: `translate3d(0, ${Math.min(scrollY * 0.32, 250)}px, 0) scale(${Math.min(1 + scrollY * 0.00015, 1.08)})`,
        willChange: 'transform' as const,
      }
    : undefined;

  return (
    <div className={styles.heroImageWrapper} style={transformStyle}>
      <Image
        src="/restaurant-interior.png"
        alt="Lord Gainsborough Interior"
        fill
        sizes="100vw"
        className={styles.heroImage}
        priority
      />
      <div className={styles.heroOverlay} />
    </div>
  );
}
