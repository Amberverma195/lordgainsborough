'use client';

import React, { useState, useEffect } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import styles from './Navbar.module.css';
import { useCart } from '@/context/CartContext';
import { ShoppingBag, User } from 'lucide-react';

const Navbar = () => {
  const [scrolled, setScrolled] = useState(false);
  const { cartCount, toggleCart } = useCart();
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  useEffect(() => {
    const handleScroll = () => {
      setScrolled(window.scrollY > 50);
    };
    window.addEventListener('scroll', handleScroll);
    
    const checkLogin = () => {
      setIsLoggedIn(!!localStorage.getItem('authToken'));
    };
    checkLogin();
    
    // Listen to local storage changes to update Navbar state instantly across pages/actions
    window.addEventListener('storage', checkLogin);
    
    return () => {
      window.removeEventListener('scroll', handleScroll);
      window.removeEventListener('storage', checkLogin);
    };
  }, []);

  return (
    <nav className={`${styles.navbar} ${scrolled ? styles.scrolled : ''}`}>
      <div className={styles.logoContainer}>
        <Link href="/">
          <Image 
            src="/NavLOGO.png" 
            alt="Lord Gainsborough Logo" 
            width={180} 
            height={60} 
            className={styles.logo}
            priority
          />
        </Link>
      </div>

      <div className={styles.navLinks}>
        <Link href="/" className={styles.navLink}>Home</Link>
        <Link href="/menu" className={styles.navLink}>Menu</Link>
        <Link href="/about" className={styles.navLink}>About</Link>
        
        <div className={styles.actionsContainer}>
          <button className={styles.cartBtn} onClick={toggleCart}>
            <ShoppingBag size={20} />
            {cartCount > 0 && <span className={styles.cartBadge}>{cartCount}</span>}
          </button>
          
          {isLoggedIn ? (
            <Link href="/profile" className={styles.loginLink} style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
              <User size={18} />
              <span>Profile</span>
            </Link>
          ) : (
            <div className={styles.authLinks}>
              <Link href="/login" className={styles.loginLink}>Login</Link>
              <Link href="/signup" className={styles.signupBtn}>Join Now</Link>
            </div>
          )}

          <Link href="/reserve" className={styles.reservationBtn}>
            Book a Table
          </Link>
        </div>
      </div>
    </nav>
  );
};

export default Navbar;
