export type ParseResult<T> =
  | { ok: true; value: T }
  | { ok: false; message: string };

export interface MenuItem {
  id: string;
  name: string;
  description: string;
  price: number;
  category: string;
  image: string;
  available: boolean;
  mostLoved: boolean;
}

export interface MenuCategory {
  id: string;
  name: string;
  description: string;
  displayOrder: number;
}

export interface PublicStoreSettings {
  id: string;
  taxRate: number;
  defaultReadyMinutes: number;
  storeName: string;
  contactPhone: string;
  contactEmail: string;
  pickupOrdersEnabled: boolean;
}

export interface SessionUser {
  token: string;
  name: string;
  email: string;
  phone: string;
  memberSince: string;
  role: string;
  canUpdateProfile: boolean;
  nextProfileUpdateAt: string | null;
  noShowCount: number;
  pickupDisabled: boolean;
}

export interface OrderLine {
  menuItemId: string;
  name: string;
  quantity: number;
  unitPrice: number;
  total: number;
  notes: string;
}

export interface CustomerOrderView {
  id: string;
  pickupDate: string;
  pickupTime: string;
  specialInstructions: string;
  status: string;
  averageReadyMinutes: number;
  items: OrderLine[];
  subtotal: number;
  tax: number;
  total: number;
  createdAt: string;
  reviewed: boolean;
  customerName: string;
  customerEmail: string;
  customerPhone: string;
}

export interface OrderCreation {
  order: CustomerOrderView;
  trackingToken: string | null;
}

export interface LoginFailure {
  code: string;
  message: string;
  retryAfterSeconds: number | null;
}

export type LoginResult =
  | { ok: true; user: SessionUser }
  | ({ ok: false } & LoginFailure);

const NO_SHOW_THRESHOLD = 3;

export function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function text(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  return value.trim();
}

function finite(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return null;
  }
  return value;
}

function booleanValue(value: unknown): boolean | null {
  return typeof value === 'boolean' ? value : null;
}

export function parseMenuItem(value: unknown): MenuItem | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = text(value.id);
  const name = text(value.name);
  const price = finite(value.price);
  if (!id || !name || price === null || price < 0) {
    return null;
  }
  const availableFlag = booleanValue(value.available) ?? booleanValue(value.isAvailable);
  const lovedFlag = booleanValue(value.mostLoved) ?? booleanValue(value.isMostLoved);
  return {
    id,
    name,
    description: text(value.description) ?? '',
    price,
    category: text(value.category) ?? '',
    image: text(value.image) ?? '',
    available: availableFlag ?? true,
    mostLoved: lovedFlag ?? false,
  };
}

export function parseMenuList(body: unknown): ParseResult<MenuItem[]> {
  if (!Array.isArray(body)) {
    return { ok: false, message: 'The menu response was not a list.' };
  }
  const items = body.map(parseMenuItem).filter((item): item is MenuItem => item !== null);
  if (body.length > 0 && items.length === 0) {
    return { ok: false, message: 'The menu response did not include any valid dishes.' };
  }
  return { ok: true, value: items };
}

export function parseCategory(value: unknown): MenuCategory | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = text(value.id);
  const name = text(value.name);
  const displayOrder = finite(value.displayOrder);
  if (!id || !name || displayOrder === null) {
    return null;
  }
  return {
    id,
    name,
    description: text(value.description) ?? '',
    displayOrder,
  };
}

export function parseCategoryList(body: unknown): ParseResult<MenuCategory[]> {
  if (!Array.isArray(body)) {
    return { ok: false, message: 'The category response was not a list.' };
  }
  const categories = body
    .map(parseCategory)
    .filter((item): item is MenuCategory => item !== null)
    .sort((left, right) => left.displayOrder - right.displayOrder || left.name.localeCompare(right.name));
  if (body.length > 0 && categories.length === 0) {
    return { ok: false, message: 'The category response did not include any valid categories.' };
  }
  return { ok: true, value: categories };
}

export function parseStoreSettings(body: unknown): ParseResult<PublicStoreSettings> {
  if (!isRecord(body)) {
    return { ok: false, message: 'Store settings were not in the expected format.' };
  }
  const taxRate = finite(body.taxRate);
  const defaultReadyMinutes = finite(body.defaultReadyMinutes);
  const pickupOrdersEnabled = booleanValue(body.pickupOrdersEnabled);
  if (taxRate === null || taxRate < 0 || defaultReadyMinutes === null || pickupOrdersEnabled === null) {
    return { ok: false, message: 'Store settings were missing tax, timing, or pickup status.' };
  }
  return {
    ok: true,
    value: {
      id: text(body.id) ?? 'default',
      taxRate,
      defaultReadyMinutes,
      storeName: text(body.storeName) ?? 'Lord Gainsborough',
      contactPhone: text(body.contactPhone) ?? '',
      contactEmail: text(body.contactEmail) ?? '',
      pickupOrdersEnabled,
    },
  };
}

export function parseSessionUser(body: unknown): ParseResult<SessionUser> {
  if (!isRecord(body)) {
    return { ok: false, message: 'The account response was not in the expected format.' };
  }
  const userObj = isRecord(body.user) ? body.user : body;
  const token = text(body.token) ?? text(userObj.token);
  const name = text(userObj.name);
  const email = text(userObj.email);
  if (!token || !name || !email) {
    return { ok: false, message: 'The account response was missing a token or contact details.' };
  }
  const noShowCount = finite(userObj.noShowCount) ?? finite(body.noShowCount) ?? 0;
  const pickupDisabled = booleanValue(userObj.pickupDisabled) ?? booleanValue(body.pickupDisabled) ?? noShowCount >= NO_SHOW_THRESHOLD;
  return {
    ok: true,
    value: {
      token,
      name,
      email,
      phone: text(userObj.phone) ?? text(body.phone) ?? '',
      memberSince: text(userObj.memberSince) ?? text(body.memberSince) ?? '',
      role: text(userObj.role) ?? text(body.role) ?? 'MEMBER',
      canUpdateProfile: booleanValue(userObj.canUpdateProfile) ?? booleanValue(body.canUpdateProfile) ?? false,
      nextProfileUpdateAt: text(userObj.nextProfileUpdateAt) ?? text(body.nextProfileUpdateAt),
      noShowCount,
      pickupDisabled,
    },
  };
}

export function parseLoginResponse(body: unknown): LoginResult {
  if (!isRecord(body)) {
    return {
      ok: false,
      code: 'MALFORMED_RESPONSE',
      message: 'The sign-in response was not in the expected format.',
      retryAfterSeconds: null,
    };
  }
  const code = text(body.code) ?? '';
  if (code && code !== 'LOGIN_SUCCESS') {
    const retry = finite(body.retryAfterSeconds);
    return {
      ok: false,
      code,
      message: text(body.message) ?? 'Unable to sign in.',
      retryAfterSeconds: retry,
    };
  }
  const session = parseSessionUser(body);
  if (!session.ok) {
    return {
      ok: false,
      code: 'MALFORMED_RESPONSE',
      message: session.message,
      retryAfterSeconds: null,
    };
  }
  return { ok: true, user: session.value };
}

function parseOrderLine(value: unknown): OrderLine | null {
  if (!isRecord(value)) {
    return null;
  }
  const name = text(value.name);
  const quantity = finite(value.quantity);
  const unitPrice = finite(value.unitPrice);
  const total = finite(value.total);
  if (!name || quantity === null || !Number.isInteger(quantity) || quantity < 1 || unitPrice === null || unitPrice < 0 || total === null) {
    return null;
  }
  return {
    menuItemId: text(value.menuItemId) ?? '',
    name,
    quantity,
    unitPrice,
    total,
    notes: text(value.notes) ?? '',
  };
}

export function parseCustomerOrder(body: unknown): ParseResult<CustomerOrderView> {
  if (!isRecord(body)) {
    return { ok: false, message: 'The order response was not in the expected format.' };
  }
  const id = text(body.id);
  if (!id) {
    return { ok: false, message: 'The order response did not include a confirmation reference.' };
  }
  if (!Array.isArray(body.items)) {
    return { ok: false, message: 'The order response did not include its items.' };
  }
  const items = body.items.map(parseOrderLine).filter((item): item is OrderLine => item !== null);
  if (body.items.length > 0 && items.length !== body.items.length) {
    return { ok: false, message: 'The order response included an item we could not read.' };
  }
  const subtotal = finite(body.subtotal);
  const tax = finite(body.tax);
  const total = finite(body.total);
  if (subtotal === null || tax === null || total === null) {
    return { ok: false, message: 'The order response did not include restaurant totals.' };
  }
  return {
    ok: true,
    value: {
      id,
      pickupDate: text(body.pickupDate) ?? '',
      pickupTime: text(body.pickupTime) ?? '',
      specialInstructions: text(body.specialInstructions) ?? '',
      status: text(body.status) ?? 'NEW',
      averageReadyMinutes: finite(body.averageReadyMinutes) ?? 0,
      items,
      subtotal,
      tax,
      total,
      createdAt: text(body.createdAt) ?? '',
      reviewed: booleanValue(body.reviewed) ?? false,
      customerName: text(body.customerName) ?? '',
      customerEmail: text(body.customerEmail) ?? '',
      customerPhone: text(body.customerPhone) ?? '',
    },
  };
}

export function parseOrderCreation(body: unknown): ParseResult<OrderCreation> {
  if (!isRecord(body) || !isRecord(body.order)) {
    return { ok: false, message: 'The restaurant did not return a confirmed order.' };
  }
  const order = parseCustomerOrder(body.order);
  if (!order.ok) {
    return order;
  }
  const trackingToken = text(body.trackingToken);
  return { ok: true, value: { order: order.value, trackingToken } };
}

export function parseOrderList(body: unknown): ParseResult<CustomerOrderView[]> {
  if (!Array.isArray(body)) {
    return { ok: false, message: 'The order history response was not a list.' };
  }
  const orders: CustomerOrderView[] = [];
  for (const entry of body) {
    const parsed = parseCustomerOrder(entry);
    if (!parsed.ok) {
      return { ok: false, message: parsed.message };
    }
    orders.push(parsed.value);
  }
  return { ok: true, value: orders };
}

export interface RecoveryChallenge {
  code: string;
  message: string;
  challengeId: string | null;
  attemptToken: string | null;
  serverTime: string | null;
  nextResendAt: string | null;
  otpExpiresAt: string | null;
  challengeExpiresAt: string | null;
  resendsRemaining: number | null;
  captchaRequired: boolean;
  retryAfterSeconds: number | null;
}

export function parseRecoveryChallenge(body: unknown): ParseResult<RecoveryChallenge> {
  if (!isRecord(body)) {
    return { ok: false, message: 'The verification response was not in the expected format.' };
  }
  const code = text(body.code);
  const message = text(body.message);
  if (!code || !message) {
    return { ok: false, message: 'The verification response was missing its status.' };
  }
  return {
    ok: true,
    value: {
      code,
      message,
      challengeId: text(body.challengeId),
      attemptToken: text(body.attemptToken),
      serverTime: text(body.serverTime),
      nextResendAt: text(body.nextResendAt),
      otpExpiresAt: text(body.otpExpiresAt),
      challengeExpiresAt: text(body.challengeExpiresAt),
      resendsRemaining: finite(body.resendsRemaining),
      captchaRequired: booleanValue(body.captchaRequired) ?? code === 'OTP_CAPTCHA_REQUIRED',
      retryAfterSeconds: finite(body.retryAfterSeconds),
    },
  };
}
