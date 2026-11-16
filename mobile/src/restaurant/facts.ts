/**
 * Facts taken from the existing website and backend, not invented for the app.
 * Dining hours come from the homepage. Pickup hours come from OrderSchedulingValidator.
 */
export const restaurantFacts = {
  name: 'Lord Gainsborough',
  established: '1985',
  tagline: 'Mastery of Taste & Timeless Elegance',
  philosophyTitle: 'A Tradition of Excellence',
  cuisine:
    'Masterfully crafted dishes using heritage recipes and local seasonal harvests.',
  service: 'Attentive, discreet, and personal service that anticipates your every need.',
  privateDining: 'Exclusive rooms for intimate gatherings and corporate events up to 50 guests.',
  addressLines: ['1800 Hyde Park Rd', 'London, ON N6H 0K1'],
  mapsUrl: 'https://maps.google.com/?q=1800+Hyde+Park+Rd,+London,+ON+N6H+0K1',
  phoneDisplay: '519-472-4168',
  phoneTel: '+15194724168',
  timezone: 'America/Toronto',
  diningHours: [
    'Monday – Thursday: 9:00 AM – 8:00 PM',
    'Friday – Saturday: 9:00 AM – 8:30 PM',
    'Sunday: 10:00 AM – 6:00 PM',
  ],
  pickupHours:
    'Pickup ordering: Mon–Thu 11:00 AM – 8:00 PM, Fri–Sat 11:00 AM – 8:30 PM, Sun 11:00 AM – 6:00 PM',
  paymentNote:
    'You will pay for your order at the restaurant when you arrive for pick-up. Cash, credit, and debit cards are accepted on-site.',
} as const;
