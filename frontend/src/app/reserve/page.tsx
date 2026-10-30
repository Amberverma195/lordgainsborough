'use client';

import React, { useState } from 'react';
import { motion } from 'framer-motion';
import { Calendar, Clock, Users, User, Mail, Phone, MessageSquare, ArrowRight, CheckCircle2, AlertCircle } from 'lucide-react';
import styles from './reserve.module.css';
import Link from 'next/link';
import { API_BASE } from '@/lib/orders';
import {
  getCalendarDayOfWeek,
  getRestaurantTodayDateString,
  getAvailableTimesForDate,
  validateReservationDateTime,
} from '@/lib/reservationDates';

export default function ReservePage() {
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

  const restaurantToday = getRestaurantTodayDateString();

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    if (name === 'date') {
      const newSlots = getAvailableTimesForDate(value);
      const isCurrentTimeValid = newSlots.some((s) => s.value === formData.time);
      setFormData((prev) => ({
        ...prev,
        date: value,
        time: isCurrentTimeValid ? prev.time : '',
      }));
      return;
    }
    setFormData((prev) => ({ ...prev, [name]: value }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    const validation = validateReservationDateTime(formData.date, formData.time);
    if (!validation.valid) {
      alert(validation.reason || 'Please select a valid reservation date and time.');
      return;
    }

    setLoading(true);
    
    try {
      const res = await fetch(`${API_BASE}/reservations`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(formData)
      });
      
      if (res.ok) {
        setSuccess(true);
      } else {
        alert('Failed to submit reservation request. Please check your information and try again.');
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
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.35 }}
        >
          <div className={styles.successIconWrapper}>
            <CheckCircle2 size={56} color="var(--secondary)" />
          </div>
          <h2>Reservation Request Received</h2>
          <div className={styles.statusBadge}>
            Status: Pending Confirmation
          </div>
          <p className={styles.successGreeting}>
            Thank you, {formData.name.split(' ')[0]}. We have received your booking request.
          </p>

          <div className={styles.successDetails}>
            <div className={styles.detailRow}>
              <span className={styles.detailLabel}>Date &amp; Time:</span>
              <strong className={styles.detailValue}>{formData.date} at {formData.time}</strong>
            </div>
            <div className={styles.detailRow}>
              <span className={styles.detailLabel}>Party Size:</span>
              <strong className={styles.detailValue}>{formData.partySize} {formData.partySize === 1 ? 'Guest' : 'Guests'}</strong>
            </div>
            <div className={styles.detailRow}>
              <span className={styles.detailLabel}>Contact:</span>
              <strong className={styles.detailValue}>{formData.phone} &bull; {formData.email}</strong>
            </div>
            {formData.specialRequests && (
              <div className={styles.detailRow}>
                <span className={styles.detailLabel}>Special Requests:</span>
                <strong className={styles.detailValue}>{formData.specialRequests}</strong>
              </div>
            )}
          </div>

          <div className={styles.noticeBox}>
            <AlertCircle size={22} color="var(--secondary)" className={styles.noticeIcon} />
            <div className={styles.noticeContent}>
              <p className={styles.noticeTitle}>Table Availability Review in Progress</p>
              <p className={styles.noticeText}>
                Your reservation is <strong>not yet confirmed</strong>. Our host team reviews all requests against dining room capacity. We will contact you at <strong>{formData.email}</strong> or <strong>{formData.phone}</strong> once your table is confirmed.
              </p>
            </div>
          </div>

          <div className={styles.successActions}>
            <Link href="/" className={styles.homeBtn}>
              Return to Home
            </Link>
            <button
              type="button"
              className={styles.newRequestBtn}
              onClick={() => {
                setSuccess(false);
                setFormData({
                  name: '',
                  email: '',
                  phone: '',
                  date: '',
                  time: '',
                  partySize: 2,
                  specialRequests: ''
                });
              }}
            >
              Submit Another Request
            </button>
          </div>
        </motion.div>
      </div>
    );
  }

  const availableTimes = getAvailableTimesForDate(formData.date);
  const dayOfWeek = getCalendarDayOfWeek(formData.date);
  const dayScheduleHint = formData.date && dayOfWeek >= 0
    ? dayOfWeek === 0
      ? 'Sunday hours: 10:00 AM – 6:00 PM'
      : dayOfWeek === 5 || dayOfWeek === 6
      ? 'Friday & Saturday hours: 9:00 AM – 8:30 PM'
      : 'Monday – Thursday hours: 9:00 AM – 8:00 PM'
    : null;

  return (
    <div className={styles.container}>
      <motion.div 
        className={styles.header}
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <h1>Request a Table</h1>
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
                  min={restaurantToday}
                />
                {dayScheduleHint && (
                  <span style={{ fontSize: '0.8rem', color: 'var(--secondary)', marginTop: '0.25rem' }}>
                    {dayScheduleHint}
                  </span>
                )}
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

          <div className={styles.formNotice}>
            <AlertCircle size={18} color="var(--secondary)" />
            <span>
              Reservations are submitted as requests with <em>Pending</em> status. Our host team will review dining room capacity and confirm your table.
            </span>
          </div>

          <button type="submit" className={styles.submitBtn} disabled={loading || !formData.date || !formData.time || !formData.name || !formData.phone}>
            {loading ? 'Submitting Request...' : 'Submit Reservation Request'} <ArrowRight size={18} />
          </button>
        </form>
      </motion.div>
    </div>
  );
}
