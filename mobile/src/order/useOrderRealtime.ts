import { useEffect, useRef } from 'react';
import { io, Socket } from 'socket.io-client';

import { CustomerOrderView, parseCustomerOrder } from '../api/parse';
import { fetchOrderSocketTicket } from '../api/orders';
import { resolveApiOrigins } from '../api/runtime';

export interface UseOrderRealtimeOptions {
  orderId?: string | null;
  token?: string | null;
  trackingToken?: string | null;
  enabled?: boolean;
  onConnect?: () => void;
  onOrderUpdated?: (order: CustomerOrderView) => void;
}

export function useOrderRealtime({
  orderId,
  token,
  trackingToken,
  enabled = true,
  onConnect,
  onOrderUpdated,
}: UseOrderRealtimeOptions): void {
  const onConnectRef = useRef(onConnect);
  const onOrderUpdatedRef = useRef(onOrderUpdated);

  useEffect(() => {
    onConnectRef.current = onConnect;
  }, [onConnect]);

  useEffect(() => {
    onOrderUpdatedRef.current = onOrderUpdated;
  }, [onOrderUpdated]);

  useEffect(() => {
    if (!enabled || !orderId) {
      return;
    }

    const socketUrl = resolveApiOrigins().socket;
    let cancelled = false;
    let socket: Socket | null = null;

    try {
      socket = io(socketUrl, {
        query: { orderId },
        auth: async (cb: (data: Record<string, unknown>) => void) => {
          try {
            const ticket = await fetchOrderSocketTicket(orderId, token, trackingToken);
            if (cancelled) {
              return;
            }
            if (ticket) {
              cb({ token: ticket });
            } else if (trackingToken) {
              cb({ trackingToken });
            } else {
              cb({});
            }
          } catch {
            if (!cancelled) {
              cb({});
            }
          }
        },
        transports: ['websocket', 'polling'],
      });

      const handleConnect = () => {
        if (!cancelled) {
          onConnectRef.current?.();
        }
      };

      const handleOrderUpdated = (payload: unknown) => {
        if (cancelled) {
          return;
        }
        const parsed = parseCustomerOrder(payload);
        if (parsed.ok && parsed.value.id === orderId) {
          onOrderUpdatedRef.current?.(parsed.value);
        }
      };

      socket.on('connect', handleConnect);
      socket.on('order.updated', handleOrderUpdated);
    } catch {
      // Do not let socket connection failure crash the app.
    }

    return () => {
      cancelled = true;
      if (socket) {
        socket.disconnect();
      }
    };
  }, [enabled, orderId, token, trackingToken]);
}
