import { CartLine, MAX_ITEM_QUANTITY, MAX_ORDER_TOTAL, MAX_TOTAL_QUANTITY } from '../cart/types';
import { estimateTotals } from '../cart/money';
import { describePickupIssue } from './pickup';

export const PICKUP_DISABLED_NO_SHOW_MESSAGE = 'Pickup disabled due to recent no-show history';
export const MAX_INSTRUCTIONS_LENGTH = 500;

export interface CheckoutDraft {
  signedIn: boolean;
  pickupDisabled: boolean;
  pickupOrdersEnabled: boolean;
  name: string;
  email: string;
  phone: string;
  lines: CartLine[];
  pickupDate: string;
  pickupTime: string;
  specialInstructions: string;
  taxRate: number;
  referenceDate?: Date;
}

export interface CheckoutProblem {
  code: string;
  message: string;
}

export function nationalPhoneDigits(phone: string): string {
  const digits = phone.replace(/\D/g, '');
  if (digits.length === 11 && digits.startsWith('1')) {
    return digits.slice(1);
  }
  return digits;
}

export function validateCheckout(draft: CheckoutDraft): CheckoutProblem | null {
  if (!draft.signedIn) {
    return { code: 'AUTHENTICATION_REQUIRED', message: 'Sign in to place a pickup order.' };
  }
  if (!draft.pickupOrdersEnabled) {
    return {
      code: 'PICKUP_ORDERS_PAUSED',
      message: 'Pickup orders are currently paused. Please check back later.',
    };
  }
  if (draft.pickupDisabled) {
    return { code: 'PICKUP_DISABLED_NO_SHOW', message: PICKUP_DISABLED_NO_SHOW_MESSAGE };
  }
  if (!draft.name.trim() || !draft.email.trim()) {
    return {
      code: 'MISSING_CUSTOMER_DETAILS',
      message: 'Your account is missing contact details. Profile editing will be available in a later release.',
    };
  }
  if (nationalPhoneDigits(draft.phone).length !== 10) {
    return {
      code: 'MISSING_CUSTOMER_DETAILS',
      message: 'Your account needs a valid 10-digit phone number before pickup checkout.',
    };
  }
  if (draft.lines.length === 0) {
    return { code: 'EMPTY_CART', message: 'Your cart is empty.' };
  }
  if (draft.lines.some((line) => !line.available)) {
    return {
      code: 'CART_ITEM_UNAVAILABLE',
      message: 'An item in your cart is no longer available. Remove it and try again.',
    };
  }
  if (draft.lines.some((line) => line.quantity < 1 || line.quantity > MAX_ITEM_QUANTITY)) {
    return {
      code: 'INVALID_ITEM_QUANTITY',
      message: `Item quantity must be between 1 and ${MAX_ITEM_QUANTITY}.`,
    };
  }
  const quantity = draft.lines.reduce((sum, line) => sum + line.quantity, 0);
  if (quantity > MAX_TOTAL_QUANTITY) {
    return {
      code: 'TOTAL_QUANTITY_EXCEEDED',
      message: `Total order quantity exceeds the limit of ${MAX_TOTAL_QUANTITY} items.`,
    };
  }
  if (draft.specialInstructions.trim().length > MAX_INSTRUCTIONS_LENGTH) {
    return {
      code: 'INSTRUCTIONS_TOO_LONG',
      message: `Special instructions must be ${MAX_INSTRUCTIONS_LENGTH} characters or fewer.`,
    };
  }
  const pickup = describePickupIssue(draft.pickupDate, draft.pickupTime, draft.referenceDate);
  if (pickup) {
    return pickup;
  }
  const totals = estimateTotals(draft.lines, draft.taxRate);
  if (totals.total > MAX_ORDER_TOTAL) {
    return {
      code: 'ORDER_TOTAL_EXCEEDED',
      message: `The estimated total exceeds the $${MAX_ORDER_TOTAL.toFixed(2)} pickup limit. The restaurant confirms the final amount.`,
    };
  }
  return null;
}

export function shouldClearIdempotencyKey(code: string): boolean {
  return code === 'IDEMPOTENT_PAYLOAD_MISMATCH';
}
