export const RESTAURANT_TIMEZONE = 'America/Toronto';
export const OPENING_MINUTES = 11 * 60;
export const CLOSING_MINUTES = 21 * 60;
export const MAX_PICKUP_ADVANCE_DAYS = 90;

export interface RestaurantNow {
  date: string;
  minutes: number;
}

export function restaurantNow(reference: Date = new Date()): RestaurantNow {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: RESTAURANT_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  });
  const parts = formatter.formatToParts(reference);
  const read = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value ?? '';
  const hour = Number.parseInt(read('hour'), 10);
  const minute = Number.parseInt(read('minute'), 10);
  return {
    date: `${read('year')}-${read('month')}-${read('day')}`,
    minutes: (Number.isFinite(hour) ? hour % 24 : 0) * 60 + (Number.isFinite(minute) ? minute : 0),
  };
}

export function isValidCalendarDate(dateStr: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) {
    return false;
  }
  const [year, month, day] = dateStr.split('-').map((part) => Number.parseInt(part, 10));
  const utc = new Date(Date.UTC(year, month - 1, day));
  return utc.getUTCFullYear() === year && utc.getUTCMonth() === month - 1 && utc.getUTCDate() === day;
}

export function addCalendarDays(dateStr: string, days: number): string {
  const [year, month, day] = dateStr.split('-').map((part) => Number.parseInt(part, 10));
  const utc = new Date(Date.UTC(year, month - 1, day + days));
  const nextYear = utc.getUTCFullYear();
  const nextMonth = String(utc.getUTCMonth() + 1).padStart(2, '0');
  const nextDay = String(utc.getUTCDate()).padStart(2, '0');
  return `${nextYear}-${nextMonth}-${nextDay}`;
}

export function isPickupDateValid(dateStr: string, reference: Date = new Date()): boolean {
  if (!isValidCalendarDate(dateStr)) {
    return false;
  }
  const today = restaurantNow(reference).date;
  return dateStr >= today && dateStr <= addCalendarDays(today, MAX_PICKUP_ADVANCE_DAYS);
}

export function formatPickupSlot(hour: number, minute: number): string {
  const period = hour >= 12 ? 'PM' : 'AM';
  const displayHour = hour > 12 ? hour - 12 : hour;
  return `${displayHour}:${String(minute).padStart(2, '0')} ${period}`;
}

export function generatePickupTimeSlots(): string[] {
  const slots: string[] = [];
  for (let hour = 11; hour <= 21; hour += 1) {
    for (const minute of [0, 30]) {
      if (hour === 21 && minute === 30) {
        continue;
      }
      slots.push(formatPickupSlot(hour, minute));
    }
  }
  return slots;
}

export function parseTimeSlotToMinutes(slot?: string | null): number | null {
  if (!slot) {
    return null;
  }
  const match = slot.trim().match(/^(0?[1-9]|1[0-2]):([0-5]\d)\s*(AM|PM)$/i);
  if (!match) {
    return null;
  }
  let hour = Number.parseInt(match[1], 10);
  const minute = Number.parseInt(match[2], 10);
  const period = match[3].toUpperCase();
  if (period === 'PM' && hour !== 12) {
    hour += 12;
  }
  if (period === 'AM' && hour === 12) {
    hour = 0;
  }
  return hour * 60 + minute;
}

export function isAsapAllowed(dateStr: string, reference: Date = new Date()): boolean {
  if (!isPickupDateValid(dateStr, reference)) {
    return false;
  }
  const now = restaurantNow(reference);
  if (dateStr !== now.date) {
    return true;
  }
  return now.minutes <= CLOSING_MINUTES;
}

export function isPickupTimeValid(
  dateStr: string,
  timeStr: string,
  reference: Date = new Date(),
): boolean {
  if (!isPickupDateValid(dateStr, reference) || !timeStr.trim()) {
    return false;
  }
  const trimmed = timeStr.trim();
  const now = restaurantNow(reference);
  const isToday = dateStr === now.date;
  if (trimmed.toLowerCase() === 'asap') {
    return !isToday || now.minutes <= CLOSING_MINUTES;
  }
  const slotMinutes = parseTimeSlotToMinutes(trimmed);
  if (slotMinutes === null || slotMinutes % 30 !== 0) {
    return false;
  }
  if (slotMinutes < OPENING_MINUTES || slotMinutes > CLOSING_MINUTES) {
    return false;
  }
  if (isToday) {
    return slotMinutes > now.minutes;
  }
  return true;
}

export function pickupChoices(dateStr: string, reference: Date = new Date()): string[] {
  const slots = generatePickupTimeSlots().filter((slot) => isPickupTimeValid(dateStr, slot, reference));
  return isAsapAllowed(dateStr, reference) ? ['asap', ...slots] : slots;
}

export function pickupDateChoices(reference: Date = new Date()): string[] {
  const today = restaurantNow(reference).date;
  return Array.from({ length: MAX_PICKUP_ADVANCE_DAYS + 1 }, (_, index) => addCalendarDays(today, index));
}

export function formatPickupDateLabel(isoDate: string): string {
  if (!isValidCalendarDate(isoDate)) {
    return isoDate;
  }
  const [year, month, day] = isoDate.split('-').map((part) => Number.parseInt(part, 10));
  return new Date(year, month - 1, day).toLocaleDateString('en-CA', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  });
}

export function formatPickupTimeLabel(value?: string | null): string {
  if (!value) {
    return '';
  }
  return value.trim().toLowerCase() === 'asap' ? 'As soon as possible' : value;
}

export interface PickupIssue {
  code: string;
  message: string;
}

export function describePickupIssue(
  dateStr: string,
  timeStr: string,
  reference: Date = new Date(),
): PickupIssue | null {
  if (!dateStr.trim()) {
    return { code: 'INVALID_PICKUP_DATE', message: 'Choose a pickup date.' };
  }
  if (!isValidCalendarDate(dateStr.trim())) {
    return { code: 'INVALID_PICKUP_DATE', message: 'Pickup date must be in YYYY-MM-DD format.' };
  }
  const today = restaurantNow(reference).date;
  if (dateStr < today) {
    return { code: 'INVALID_PICKUP_DATE', message: 'Pickup date cannot be in the past.' };
  }
  if (dateStr > addCalendarDays(today, MAX_PICKUP_ADVANCE_DAYS)) {
    return {
      code: 'INVALID_PICKUP_DATE',
      message: `Pickup date cannot be more than ${MAX_PICKUP_ADVANCE_DAYS} days in advance.`,
    };
  }
  if (!timeStr.trim()) {
    return { code: 'INVALID_PICKUP_TIME', message: 'Choose a pickup time.' };
  }
  if (timeStr.trim().toLowerCase() === 'asap') {
    if (!isAsapAllowed(dateStr, reference)) {
      return {
        code: 'PICKUP_TIME_CLOSED',
        message: 'Pickup orders are closed for today. Operating hours are 11:00 AM to 9:00 PM.',
      };
    }
    return null;
  }
  if (!isPickupTimeValid(dateStr, timeStr, reference)) {
    const minutes = parseTimeSlotToMinutes(timeStr);
    const now = restaurantNow(reference);
    if (dateStr === now.date && minutes !== null && minutes <= now.minutes) {
      return { code: 'PAST_PICKUP_TIME', message: 'Pickup time cannot be in the past.' };
    }
    return {
      code: 'INVALID_PICKUP_TIME',
      message: 'Pickup time must be between 11:00 AM and 9:00 PM in 30-minute intervals, or as soon as possible.',
    };
  }
  return null;
}
