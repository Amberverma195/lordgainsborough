'use client';

import React, { useState } from 'react';
import { motion } from 'framer-motion';
import { Calendar, Clock, Users, User, Mail, Phone, MessageSquare, ArrowRight } from 'lucide-react';
import styles from './reserve.module.css';
import { useRouter } from 'next/navigation';

export default function ReservePage() {
  const router = useRouter();
  const [loading, setLoading] = useState(false);
  const [success, setSuccess] = useState(false);
  const [formData, setFormData] = useState({
    name: '',
    email: '',
    phone: '',
    date: '',
    time: '',
    partySize: 2,
    specialRequests: ''
  });

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    
    try {
      const res = await fetch('http://localhost:8082/api/reservations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(formData)
      });
      
      if (res.ok) {
        setSuccess(true);
        setTimeout(() => {
          router.push('/');
        }, 3000);
      } else {
        alert('Failed to submit reservation. Please try again.');
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Unknown error';
      console.error(err);
      alert(`An error occurred: ${message}. Please ensure the backend is running and try again.`);
    } finally {
      setLoading(false);
    }
  };

  if (success) {
    return (
      <div className={styles.container}>
        <motion.div 
          className={`${styles.successCard} premium-card`}
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
        >
          <div className={styles.successIcon}>✨</div>
          <h2>Reservation Confirmed</h2>
          <p>We look forward to hosting you, {formData.name.split(' ')[0]}.</p>
          <p className={styles.successDetails}>
            Your table for {formData.partySize} is booked on {formData.date} at {formData.time}.
          </p>
          <p className={styles.redirectText}>Returning to home page...</p>
        </motion.div>
      </div>
    );
  }

  const getAvailableTimes = () => {
    if (!formData.date) return [];
    
    const dateObj = new Date(formData.date);
    const day = dateObj.getDay(); // 0 = Sunday, 1 = Monday, ..., 6 = Saturday
    
    let startHour = 9;
    let endHour = 20; // 8 PM
    let endMinutes = 0;

    if (day === 0) { // Sunday
      startHour = 10;
      endHour = 18; // 6 PM
    } else if (day === 5 || day === 6) { // Fri, Sat
      endHour = 20;
      endMinutes = 30; // 8:30 PM
    }

    const times = [];
    const current = new Date();
    const isToday = dateObj.toDateString() === current.toDateString();

    for (let hour = startHour; hour <= endHour; hour++) {
      for (const min of [0, 30]) {
        if (hour === endHour && min > endMinutes) break;
        
        // Don't show past times for today
        if (isToday) {
          if (hour < current.getHours() || (hour === current.getHours() && min <= current.getMinutes())) {
            continue;
          }
        }

        const period = hour >= 12 ? 'PM' : 'AM';
        const displayHour = hour > 12 ? hour - 12 : (hour === 0 ? 12 : hour);
        const timeStr = `${hour.toString().padStart(2, '0')}:${min.toString().padStart(2, '0')}`;
        const displayStr = `${displayHour}:${min.toString().padStart(2, '0')} ${period}`;
        
        times.push({ value: timeStr, label: displayStr });
      }
    }
    return times;
  };

  const availableTimes = getAvailableTimes();

  return (
    <div className={styles.container}>
      <motion.div 
        className={styles.header}
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <h1>Reserve a Table</h1>
        <p>Experience culinary excellence at Lord Gainsborough.</p>
      </motion.div>

      <motion.div 
        className={`${styles.formWrapper} premium-card`}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
      >
        <form onSubmit={handleSubmit} className={styles.form}>
          
          <div className={styles.formSection}>
            <h3>Reservation Details</h3>
            <div className={styles.grid}>
              <div className={styles.inputGroup}>
                <label><Calendar size={16} /> Date</label>
                <input 
                  type="date" 
                  name="date" 
                  value={formData.date} 
                  onChange={handleChange} 
                  required 
                  min={new Date().toISOString().split('T')[0]}
                />
              </div>
              <div className={styles.inputGroup}>
                <label><Clock size={16} /> Time</label>
                <select 
                  name="time" 
                  value={formData.time} 
                  onChange={handleChange} 
                  required
                  disabled={!formData.date}
                >
                  <option value="">{formData.date ? 'Select a time' : 'Select a date first'}</option>
                  {availableTimes.map(t => (
                    <option key={t.value} value={t.value}>{t.label}</option>
                  ))}
                </select>
              </div>
              <div className={styles.inputGroup}>
                <label><Users size={16} /> Party Size</label>
                <select name="partySize" value={formData.partySize} onChange={handleChange} required>
                  {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(num => (
                    <option key={num} value={num}>{num} {num === 1 ? 'Person' : 'People'}</option>
                  ))}
                </select>
              </div>
            </div>
          </div>

          <div className={styles.formSection}>
            <h3>Contact Information</h3>
            <div className={styles.grid}>
              <div className={styles.inputGroup}>
                <label><User size={16} /> Full Name</label>
                <input 
                  type="text" 
                  name="name" 
                  placeholder="Enter your full name" 
                  value={formData.name} 
                  onChange={handleChange} 
                  required 
                />
              </div>
              <div className={styles.inputGroup}>
                <label><Phone size={16} /> Phone Number</label>
                <input 
                  type="tel" 
                  name="phone" 
                  placeholder="(555) 555-5555" 
                  value={formData.phone} 
                  onChange={(e) => {
                    const value = e.target.value.replace(/\D/g, '');
                    let formatted = value;
                    if (value.length > 3 && value.length <= 6) {
                      formatted = `(${value.slice(0, 3)}) ${value.slice(3)}`;
                    } else if (value.length > 6) {
                      formatted = `(${value.slice(0, 3)}) ${value.slice(3, 6)}-${value.slice(6, 10)}`;
                    }
                    setFormData({ ...formData, phone: formatted });
                  }} 
                  required 
                  maxLength={14}
                />
              </div>
              <div className={`${styles.inputGroup} ${styles.fullWidth}`}>
                <label><Mail size={16} /> Email Address</label>
                <input 
                  type="email" 
                  name="email" 
                  placeholder="Enter email address" 
                  value={formData.email} 
                  onChange={handleChange} 
                  required 
                />
              </div>
            </div>
          </div>

          <div className={styles.formSection}>
            <div className={`${styles.inputGroup} ${styles.fullWidth}`}>
              <label><MessageSquare size={16} /> Special Requests (Optional)</label>
              <textarea 
                name="specialRequests" 
                placeholder="Dietary requirements, special occasions..."
                rows={3}
                value={formData.specialRequests}
                onChange={handleChange}
              />
            </div>
          </div>

          <button type="submit" className={styles.submitBtn} disabled={loading || !formData.date || !formData.time || !formData.name || !formData.phone}>
            {loading ? 'Confirming...' : 'Confirm Reservation'} <ArrowRight size={18} />
          </button>
        </form>
      </motion.div>
    </div>
  );
}
