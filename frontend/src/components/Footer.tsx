'use client';

import React from 'react';
import Link from 'next/link';
import Image from 'next/image';
import styles from './Footer.module.css';
import { motion } from 'framer-motion';
import { Camera, Globe, Mail, Phone, MapPin } from 'lucide-react';
import { RESTAURANT_PHONE_DISPLAY, RESTAURANT_PHONE_TEL } from '@/lib/phone';

const Footer = () => {
  return (
    <footer className={styles.footer}>
      <div className={styles.container}>
        <div className={styles.topSection}>
          <div className={styles.brand}>
            <Image 
              src="/NavLOGO.png" 
              alt="Lord Gainsborough Logo" 
              width={200} 
              height={70} 
              className={styles.logo}
            />
            <p className={styles.tagline}>
              Crafting culinary excellence and unforgettable moments in the heart of the city since 1920.
            </p>
            <div className={styles.socials}>
              <motion.a href="#" whileHover={{ y: -3, color: 'var(--secondary)' }}><Camera size={20} /></motion.a>
              <motion.a href="#" whileHover={{ y: -3, color: 'var(--secondary)' }}><Globe size={20} /></motion.a>
              <motion.a href="#" whileHover={{ y: -3, color: 'var(--secondary)' }}><Globe size={20} /></motion.a>
            </div>
          </div>

          <div className={styles.links}>
            <h3>Explore</h3>
            <Link href="/">Home</Link>
            <Link href="/menu">Our Menu</Link>
            <Link href="/about">Our Story</Link>
            <Link href="/reserve">Reservations</Link>
          </div>

          <div className={styles.hours}>
            <h3>Opening Hours</h3>
            <ul>
              <li><span>Mon - Thu:</span> 9 AM - 8 PM</li>
              <li><span>Fri - Sat:</span> 9 AM - 8:30 PM</li>
              <li><span>Sun:</span> 10 AM - 6 PM</li>
            </ul>
          </div>

          <div className={styles.contact}>
            <h3>Visit Us</h3>
            <p><MapPin size={18} /> 1800 Hyde Park Rd, London, ON N6H 0K1</p>
            <p>
              <Phone size={18} />{' '}
              <a href={`tel:${RESTAURANT_PHONE_TEL}`}>{RESTAURANT_PHONE_DISPLAY}</a>
            </p>
            <p><Mail size={18} /> dine@lordgainsborough.com</p>
          </div>
        </div>

        <div className={styles.bottomSection}>
          <p>&copy; {new Date().getFullYear()} Lord Gainsborough Restaurant. All rights reserved.</p>
          <div className={styles.legal}>
            <Link href="#">Privacy Policy</Link>
            <Link href="#">Terms of Service</Link>
          </div>
        </div>
      </div>
    </footer>
  );
};

export default Footer;
