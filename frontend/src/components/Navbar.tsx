'use client';

import React, { useState, useEffect } from 'react';
import Image from 'next/image';
import Link from 'next/link';
import styles from './Navbar.module.css';
import { useCart } from '@/context/CartContext';
import { ShoppingBag, User, Menu, X } from 'lucide-react';

const Navbar = () => {
  const [scrolled, setScrolled] = useState(false);
  const { cartCount, toggleCart } = useCart();
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    const handleScroll = () => {
      setScrolled(window.scrollY > 40);
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
        <Link href="/" aria-label="Lord Gainsborough Home">
          <Image 
            src="/NavLOGO.png" 
            alt="Lord Gainsborough Logo" 
            width={160} 
            height={52} 
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
          <button
            type="button"
            className={styles.mobileToggle}
            aria-label={menuOpen ? 'Close navigation' : 'Open navigation'}
            aria-expanded={menuOpen}
            aria-controls="mobile-navigation"
            onClick={() => setMenuOpen(open => !open)}
          >
            {menuOpen ? <X size={22} /> : <Menu size={22} />}
          </button>

          <button
            type="button"
            className={styles.cartBtn}
            onClick={toggleCart}
            aria-label={`Open cart (${cartCount} items)`}
          >
            <ShoppingBag size={20} />
            {cartCount > 0 && <span className={styles.cartBadge}>{cartCount}</span>}
          </button>

          <Link
            href={isLoggedIn ? '/profile' : '/login'}
            className={styles.profileBtn}
            aria-label={isLoggedIn ? 'Profile' : 'Login'}
          >
            <User size={20} />
          </Link>

          <Link href="/reserve" className={styles.reservationBtn}>
            <span className={styles.reservationBtnTextFull}>Book a Table</span>
            <span className={styles.reservationBtnTextShort}>Reserve</span>
          </Link>
        </div>
      </div>

      {menuOpen && (
        <div id="mobile-navigation" className={styles.mobilePanel}>
          <Link href="/" onClick={() => setMenuOpen(false)}>Home</Link>
          <Link href="/menu" onClick={() => setMenuOpen(false)}>Menu</Link>
          <Link href="/about" onClick={() => setMenuOpen(false)}>About</Link>
          <Link href="/reserve" className={styles.mobilePanelReserveBtn} onClick={() => setMenuOpen(false)}>
            Book a Table
          </Link>
        </div>
      )}
    </nav>
  );
};

export default Navbar;
