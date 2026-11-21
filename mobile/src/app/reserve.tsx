import { ReservationPicker } from '../components/ReservationPicker';
import { useAuth } from '../auth/AuthContext';
import {
  reservationKeys,
  reservationDates,
  reservationTimes,
} from '../reservations/choices';
import * as SecureStore from 'expo-secure-store';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useEffect, useRef, useState } from 'react';
import { Linking, Text, TextInput } from 'react-native';
import { apiRequest } from '../api/client';
import { ApiError } from '../api/errors';
import { AppHeader } from '../components/AppHeader';
import { Button } from '../components/Button';
import { Screen } from '../components/Screen';
import { restaurantFacts } from '../restaurant/facts';
import { isValidEmail, isValidPhone } from '../auth/validation';
import { colors } from '../theme/tokens';
const labels = {
  name: 'Guest name',
  email: 'Email address',
  phone: 'Telephone number',
  date: 'Date YYYY-MM-DD',
  time: 'Time HH:mm (24-hour)',
  partySize: 'Party size (1-10)',
  specialRequests: 'Special requests',
};

function requestReference(body: unknown): string {
  if (
    !body ||
    typeof body !== 'object' ||
    !('id' in body) ||
    !('status' in body) ||
    body.status !== 'PENDING' ||
    typeof body.id !== 'string' ||
    !/^[A-Za-z0-9_-]{1,128}$/.test(body.id)
  )
    throw new Error('Invalid reservation response');
  return body.id;
}
export default function ReserveScreen() {
  const { user } = useAuth();
  const keys = reservationKeys(user?.email);
  const KEY = keys.pending;
  const RESERVATION_REFERENCE_KEY = keys.reference;
  const [loadedFor, setLoadedFor] = useState('');
  const currentScope = useRef(KEY);
  useEffect(() => {
    currentScope.current = KEY;
  }, [KEY]);
  const [form, setForm] = useState({
    name: '',
    email: '',
    phone: '',
    date: '',
    time: '',
    partySize: '2',
    specialRequests: '',
  });
  const [state, setState] = useState('loading');
  const [error, setError] = useState('');
  const [reference, setReference] = useState<string | null>(null);
  const guard = useRef(false);
  useEffect(() => {
    let live = true;
    void Promise.all([
      AsyncStorage.getItem(KEY),
      SecureStore.getItemAsync(RESERVATION_REFERENCE_KEY),
    ])
      .then(([marker, saved]) => {
        const id = saved ? requestReference(JSON.parse(saved)) : null;
        if (!live) return;
        setForm({name:'',email:'',phone:'',date:'',time:'',partySize:'2',specialRequests:''});
        setError('');
        setLoadedFor(KEY);
        setReference(id);
        setState(marker === 'sent' ? 'sent' : marker ? 'uncertain' : 'ready');
        if (marker === 'sent' && !id)
          setError(
            'Your request was received, but its reference is unavailable on this device. Call the restaurant for help.',
          );
      })
      .catch(() => {
        if (live) {
          setLoadedFor(KEY);
          setReference(null);
          setState('uncertain');
          setError(
            'Saved request details could not be read. Call the restaurant before submitting again.',
          );
        }
      });
    return () => {
      live = false;
    };
  }, [KEY, RESERVATION_REFERENCE_KEY]);
  async function submit() {
    if (guard.current || state !== 'ready' || loadedFor !== KEY) return;
    if (
      !form.name.trim() ||
      !isValidEmail(form.email) ||
      !isValidPhone(form.phone) ||
      !reservationDates().includes(form.date) ||
      !reservationTimes(form.date).includes(form.time) ||
      !Number.isInteger(Number(form.partySize)) ||
      Number(form.partySize) < 1 ||
      Number(form.partySize) > 10
    ) {
      setError(
        'Enter name, valid email and Canadian/US phone, date YYYY-MM-DD, time HH:00 or HH:30, and 1-10 guests.',
      );
      return;
    }
    guard.current = true;
    setState('sending');
    setError('');
    let accepted = false;
    try {
      await AsyncStorage.setItem(KEY, 'uncertain');
      const response = await apiRequest('/api/reservations', {
        method: 'POST',
        body: {
          ...form,
          name: form.name.trim(),
          email: form.email.trim().toLowerCase(),
          partySize: Number(form.partySize),
        },
      });
      const id = requestReference(response.body);
      accepted = true;
      if (currentScope.current === KEY) {
        setReference(id);
        setState('sent');
      }
      await SecureStore.setItemAsync(
        RESERVATION_REFERENCE_KEY,
        JSON.stringify({ id, status: 'PENDING' }),
      );
      await AsyncStorage.setItem(KEY, 'sent');
    } catch (failure) {
      if (currentScope.current !== KEY) return;
      if (accepted) {
        setError(
          'Your request was received, but its reference could not be saved. Copy the reference shown here and call the restaurant before submitting again.',
        );
      } else if (
        failure instanceof ApiError &&
        failure.status >= 400 &&
        failure.status < 500
      ) {
        await AsyncStorage.removeItem(KEY);
        setState('ready');
        setError(
          failure.status === 429
            ? 'Too many requests. Retry in ' +
                (failure.retryAfterSeconds ?? 60) +
                ' seconds.'
            : 'Check your details and choose a future time within dining hours.',
        );
      } else {
        setState('uncertain');
        setError(
          'Delivery could not be confirmed. Call before submitting again to avoid duplicates.',
        );
      }
    } finally {
      guard.current = false;
    }
  }
  if (loadedFor !== KEY)
    return (
      <Screen>
        <Text>Loading reservation requests...</Text>
      </Screen>
    );
  return (
    <Screen scroll>
      <AppHeader title="Book a Table" eyebrow="Reservations" showBack />
      <Text style={{ color: colors.text }}>
        Request a table. Submission is pending confirmation, not a guaranteed
        reservation. All dates and times are America/Toronto.
      </Text>
      {restaurantFacts.diningHours.map((line) => (
        <Text key={line} style={{ color: colors.textSecondary }}>
          {line}
        </Text>
      ))}
      {state === 'ready' || state === 'sending' ? (
        <>
          {(['name', 'email', 'phone', 'specialRequests'] as const).map(
            (key) => (
              <TextInput
                key={key}
                accessibilityLabel={labels[key]}
                keyboardType={
                  key === 'email'
                    ? 'email-address'
                    : key === 'phone'
                      ? 'phone-pad'
                      : 'default'
                }
                multiline={key === 'specialRequests'}
                placeholder={labels[key]}
                placeholderTextColor={colors.textMuted}
                value={form[key]}
                editable={state === 'ready'}
                maxLength={key === 'specialRequests' ? 1000 : 255}
                onChangeText={(value) =>
                  setForm((old) => ({ ...old, [key]: value }))
                }
                style={{ color: colors.text, minHeight: 52 }}
              />
            ),
          )}
          <ReservationPicker
            date={form.date}
            time={form.time}
            partySize={form.partySize}
            disabled={state !== 'ready'}
            onChange={(value) => setForm((old) => ({ ...old, ...value }))}
          />
          <Button
            label="Submit reservation request"
            loading={state === 'sending'}
            onPress={() => void submit()}
          />
        </>
      ) : null}
      {state === 'loading' ? <Text>Checking saved requests...</Text> : null}
      {state === 'sent' ? (
        <Text style={{ color: colors.text }}>
          Request received - pending confirmation. The team will contact you.
        </Text>
      ) : null}
      {reference ? (
        <Text
          selectable
          accessibilityLabel="Reservation request reference"
          style={{ color: colors.text }}
        >
          Last received request reference: {reference}. Pending confirmation at
          submission; call the restaurant for current status.
        </Text>
      ) : null}
      {state === 'sent' || state === 'uncertain' ? (
        <>
          <Text style={{ color: colors.text }}>
            Call to check your previous request before making another.
          </Text>
          <Button
            label="I checked with the restaurant - start another request"
            variant="ghost"
            onPress={() => {
              void AsyncStorage.removeItem(KEY)
                .then(() => setState('ready'))
                .catch(() =>
                  setError(
                    'Device storage could not clear the previous request. Please call the restaurant.',
                  ),
                );
            }}
          />
        </>
      ) : null}
      {error ? (
        <Text accessibilityRole="alert" style={{ color: colors.danger }}>
          {error}
        </Text>
      ) : null}
      <Button
        label={'Call ' + restaurantFacts.phoneDisplay}
        onPress={() => void Linking.openURL('tel:' + restaurantFacts.phoneTel)}
      />
    </Screen>
  );
}
