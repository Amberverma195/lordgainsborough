'use client';

import React, { useState } from 'react';
import styles from './reservations.module.css';
import { motion } from 'framer-motion';

export default function ReservationsPage() {
  const [submitted, setSubmitted] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
  };

  if (submitted) {
    return (
      <div className={styles.container}>
        <motion.div 
          className="premium-card" 
          style={{ textAlign: 'center', padding: '4rem' }}
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
        >
          <h2 style={{ color: 'var(--secondary)', marginBottom: '1rem' }}>Reservation Confirmed</h2>
          <p>Thank you for choosing Lord Gainsborough. We look forward to welcoming you.</p>
          <button 
            className={styles.submitBtn} 
            style={{ width: 'auto', marginTop: '2rem' }}
            onClick={() => setSubmitted(false)}
          >
            Back to Bookings
          </button>
        </motion.div>
      </div>
    );
  }

  return (
    <div className={styles.container}>
      <motion.h1 
        className={styles.title}
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        Table Reservation
      </motion.h1>

      <motion.div 
        className="premium-card"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.2 }}
      >
        <form className={styles.form} onSubmit={handleSubmit}>
          <div className={styles.row}>
            <div className={styles.inputGroup}>
              <label className={styles.label}>First Name</label>
              <input type="text" className={styles.input} required placeholder="John" />
            </div>
            <div className={styles.inputGroup}>
              <label className={styles.label}>Last Name</label>
              <input type="text" className={styles.input} required placeholder="Doe" />
            </div>
          </div>

          <div className={styles.row}>
            <div className={styles.inputGroup}>
              <label className={styles.label}>Email</label>
              <input type="email" className={styles.input} required placeholder="john@example.com" />
            </div>
            <div className={styles.inputGroup}>
              <label className={styles.label}>Phone Number</label>
              <input type="tel" className={styles.input} required placeholder="+44 20 7946 0000" />
            </div>
          </div>

          <div className={styles.row}>
            <div className={styles.inputGroup}>
              <label className={styles.label}>Date</label>
              <input type="date" className={styles.input} required />
            </div>
            <div className={styles.inputGroup}>
              <label className={styles.label}>Time</label>
              <select className={styles.input} required>
                <option value="">Select Time</option>
                <option>18:00</option>
                <option>18:30</option>
                <option>19:00</option>
                <option>19:30</option>
                <option>20:00</option>
                <option>20:30</option>
                <option>21:00</option>
              </select>
            </div>
          </div>

          <div className={styles.inputGroup}>
            <label className={styles.label}>Guests</label>
            <input type="number" className={styles.input} min="1" max="10" required placeholder="2" />
          </div>

          <div className={styles.inputGroup}>
            <label className={styles.label}>Special Requests</label>
            <textarea className={styles.input} rows={4} placeholder="Allergies, special occasions, etc."></textarea>
          </div>

          <button type="submit" className={styles.submitBtn}>
            Confirm Reservation
          </button>
        </form>
      </motion.div>
    </div>
  );
}
