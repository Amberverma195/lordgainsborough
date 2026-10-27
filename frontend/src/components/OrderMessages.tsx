'use client';

import { useState } from 'react';
import { API_BASE, getStoredTrackingToken, ORDER_TRACKING_TOKEN_HEADER, type OrderMessage } from '@/lib/orders';
import { getAuthHeadersOnly } from '@/lib/auth';
import styles from './OrderMessages.module.css';

export default function OrderMessages({ orderId, messages, kitchen = false, onSent }: {
  orderId: string; messages: OrderMessage[]; kitchen?: boolean; onSent: () => void;
}) {
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  async function send(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!text.trim() || sending) return;
    setSending(true); setError('');
    const headers = { ...getAuthHeadersOnly(), 'Content-Type': 'application/json' } as Record<string, string>;
    const token = !kitchen && getStoredTrackingToken(orderId);
    if (token) headers[ORDER_TRACKING_TOKEN_HEADER] = token;
    try {
      const response = await fetch(`${API_BASE}/${kitchen ? 'kds/orders' : 'orders'}/${orderId}/messages`, {
        method: 'POST', headers, body: JSON.stringify({ text: text.trim() }),
      });
      if (!response.ok) throw new Error('Message not sent');
      setText(''); onSent();
    } catch { setError('Message could not be sent. Please try again.'); }
    finally { setSending(false); }
  }
  return (
    <details className={styles.panel}>
      <summary>Messages ({messages.length})</summary>
      <div aria-live="polite" className={styles.messages}>
        {messages.length ? messages.map(message => (
          <p key={message.id}><strong>{message.senderName}</strong>: {message.text}</p>
        )) : <p>No messages yet.</p>}
      </div>
      <form onSubmit={send} className={styles.form}>
        <label htmlFor={`message-${orderId}`}>{kitchen ? 'Message customer' : 'Message kitchen'}</label>
        <textarea id={`message-${orderId}`} value={text} onChange={event => setText(event.target.value)} rows={2} maxLength={2000} />
        <button type="submit" disabled={sending || !text.trim()}>{sending ? 'Sending…' : 'Send message'}</button>
        {error && <p role="alert">{error}</p>}
      </form>
    </details>
  );
}
