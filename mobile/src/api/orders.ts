import { apiRequest } from './client';
import { ApiError } from './errors';
import { CustomerOrderView, parseOrderCreation, parseOrderList, parseCustomerOrder } from './parse';
import { CartLine } from '../cart/types';

export const IDEMPOTENCY_HEADER = 'Idempotency-Key';
export const TRACKING_TOKEN_HEADER = 'X-Order-Tracking-Token';

export interface OrderDraft {
  customerName: string;
  customerEmail: string;
  customerPhone: string;
  pickupDate: string;
  pickupTime: string;
  specialInstructions: string;
  averageReadyMinutes: number;
  idempotencyKey: string;
  lines: CartLine[];
  subtotal: number;
  tax: number;
  total: number;
}

export function buildOrderBody(draft: OrderDraft): Record<string, unknown> {
  return {
    customerName: draft.customerName.trim(),
    customerEmail: draft.customerEmail.trim(),
    customerPhone: draft.customerPhone.trim(),
    pickupDate: draft.pickupDate,
    pickupTime: draft.pickupTime,
    specialInstructions: draft.specialInstructions.trim(),
    averageReadyMinutes: draft.averageReadyMinutes,
    idempotencyKey: draft.idempotencyKey,
    items: draft.lines.map((line) => ({
      menuItemId: line.menuItemId,
      name: line.name,
      quantity: line.quantity,
      unitPrice: line.unitPrice,
      total: Math.round(line.unitPrice * line.quantity * 100) / 100,
    })),
    subtotal: draft.subtotal,
    tax: draft.tax,
    total: draft.total,
  };
}

export async function createOrder(token: string, draft: OrderDraft) {
  const response = await apiRequest('/api/orders', {
    method: 'POST',
    token,
    headers: { [IDEMPOTENCY_HEADER]: draft.idempotencyKey },
    body: buildOrderBody(draft),
  });
  const parsed = parseOrderCreation(response.body);
  if (!parsed.ok) {
    throw new ApiError({
      status: response.status,
      code: 'UNCONFIRMED_ORDER',
      message: 'The restaurant response could not be verified. Check Orders before placing the order again.',
    });
  }
  return parsed.value;
}

export async function fetchOrderHistory(token: string): Promise<CustomerOrderView[]> {
  const response = await apiRequest('/api/auth/orders', { token });
  const parsed = parseOrderList(response.body);
  if (!parsed.ok) {
    throw new ApiError({ status: response.status, code: 'MALFORMED_RESPONSE', message: parsed.message });
  }
  return parsed.value;
}

export async function fetchOrder(
  id: string,
  token: string,
  trackingToken?: string | null,
): Promise<CustomerOrderView> {
  const headers: Record<string, string> = {};
  if (trackingToken) {
    headers[TRACKING_TOKEN_HEADER] = trackingToken;
  }
  const response = await apiRequest(`/api/orders/${encodeURIComponent(id)}`, { token, headers });
  const parsed = parseCustomerOrder(response.body);
  if (!parsed.ok) {
    throw new ApiError({ status: response.status, code: 'MALFORMED_RESPONSE', message: parsed.message });
  }
  return parsed.value;
}

export async function fetchOrderByIdempotencyKey(
  idempotencyKey: string,
  token: string,
): Promise<CustomerOrderView | null> {
  try {
    const response = await apiRequest(
      `/api/orders/by-idempotency/${encodeURIComponent(idempotencyKey)}`,
      { token },
    );
    const parsed = parseCustomerOrder(response.body);
    if (!parsed.ok) {
      throw new ApiError({
        status: response.status,
        code: 'MALFORMED_RESPONSE',
        message: parsed.message,
      });
    }
    return parsed.value;
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}

export async function fetchOrderSocketTicket(
  id: string,
  token?: string | null,
  trackingToken?: string | null,
): Promise<string | null> {
  const headers: Record<string, string> = {};
  if (trackingToken) {
    headers[TRACKING_TOKEN_HEADER] = trackingToken;
  }
  try {
    const response = await apiRequest(`/api/orders/${encodeURIComponent(id)}/socket-ticket`, {
      token: token || undefined,
      headers,
    });
    if (response.body && typeof response.body === 'object' && 'ticket' in response.body) {
      const ticket = (response.body as { ticket?: unknown }).ticket;
      if (typeof ticket === 'string' && ticket.trim().length > 0) {
        return ticket.trim();
      }
    }
    return null;
  } catch {
    return null;
  }
}

export interface ServerCartItem {
  menuItemId: string;
  name: string;
  description: string;
  price: number;
  category: string;
  image: string;
  quantity: number;
}

export async function fetchServerCart(token: string): Promise<ServerCartItem[]> {
  const response = await apiRequest('/api/cart', { token });
  if (!response.body || typeof response.body !== 'object' || !('items' in response.body)) {
    return [];
  }
  const items = (response.body as { items?: unknown }).items;
  if (!Array.isArray(items)) {
    return [];
  }
  return items.flatMap((item) => {
    if (!item || typeof item !== 'object') {
      return [];
    }
    const record = item as Record<string, unknown>;
    if (typeof record.menuItemId !== 'string' || typeof record.name !== 'string') {
      return [];
    }
    if (typeof record.price !== 'number' || typeof record.quantity !== 'number') {
      return [];
    }
    return [{
      menuItemId: record.menuItemId,
      name: record.name,
      description: typeof record.description === 'string' ? record.description : '',
      price: record.price,
      category: typeof record.category === 'string' ? record.category : '',
      image: typeof record.image === 'string' ? record.image : '',
      quantity: record.quantity,
    }];
  });
}

export async function saveServerCart(token: string, items: ServerCartItem[]): Promise<void> {
  await apiRequest('/api/cart', { method: 'PUT', token, body: items, notifyUnauthorized: false });
}
