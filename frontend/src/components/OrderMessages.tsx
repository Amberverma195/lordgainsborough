'use client';

import { apiFetch } from '@/lib/apiFetch';
import { useState } from 'react';
import { API_BASE, KDS_API_BASE, getStoredTrackingToken, ORDER_TRACKING_TOKEN_HEADER, type OrderMessage } from '@/lib/orders';
import { getAuthHeadersOnly } from '@/lib/auth';
import { MessageSquare, Send } from 'lucide-react';
import styles from './OrderMessages.module.css';

function formatMessageTime(iso?: string) {
  if (!iso) return '';
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return '';
  return parsed.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

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
      const response = await apiFetch(`${kitchen ? KDS_API_BASE : API_BASE}/${kitchen ? 'kds/orders' : 'orders'}/${orderId}/messages`, {
        method: 'POST', headers, body: JSON.stringify({ text: text.trim() }),
      });
      if (!response.ok) throw new Error('Message not sent');
      setText(''); onSent();
    } catch { setError('Message could not be sent. Please try again.'); }
    finally { setSending(false); }
  }

  return (
    <details className={kitchen ? styles.panel : styles.panelCustomer}>
      <summary className={kitchen ? undefined : styles.summaryCustomer}>
        <span>
          {!kitchen && <MessageSquare size={16} style={{ display: 'inline', marginRight: '0.45rem', verticalAlign: 'text-bottom' }} />}
          Messages ({messages.length})
        </span>
      </summary>
      <div aria-live="polite" className={kitchen ? styles.messages : styles.messagesCustomer}>
        {messages.length ? messages.map(message => (
          kitchen ? (
            <p key={message.id}><strong>{message.senderName}</strong>: {message.text}</p>
          ) : (
            <div key={message.id} className={message.senderName.toLowerCase().includes('kitchen') ? styles.messageStaff : styles.messageCustomer}>
              <div className={styles.messageHeader}>
                <span className={styles.msgSender}>{message.senderName}</span>
                {message.createdAt && <span className={styles.msgTime}>{formatMessageTime(message.createdAt)}</span>}
              </div>
              <p className={styles.msgBody}>{message.text}</p>
            </div>
          )
        )) : (
          <p className={kitchen ? undefined : styles.noMessages}>
            {kitchen ? 'No messages yet.' : 'No messages yet. Send a note if you have special questions for the kitchen.'}
          </p>
        )}
      </div>
      <form onSubmit={send} className={kitchen ? styles.form : styles.formCustomer}>
        <label htmlFor={`message-${orderId}`} className={kitchen ? undefined : styles.formLabel}>
          {kitchen ? 'Message customer' : 'Message kitchen'}
        </label>
        <textarea
          id={`message-${orderId}`}
          value={text}
          onChange={event => setText(event.target.value)}
          rows={2}
          maxLength={2000}
          placeholder={kitchen ? '' : 'Type a question or update for the kitchen team...'}
          className={kitchen ? undefined : styles.textareaCustomer}
        />
        <button type="submit" disabled={sending || !text.trim()} className={kitchen ? undefined : styles.buttonCustomer}>
          {!kitchen && <Send size={14} style={{ display: 'inline', marginRight: '0.35rem', verticalAlign: 'middle' }} />}
          {sending ? 'Sending…' : 'Send message'}
        </button>
        {error && <p role="alert" className={kitchen ? undefined : styles.errorAlert}>{error}</p>}
      </form>
    </details>
  );
}
