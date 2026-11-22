import { onAccountChange } from '../auth/lifecycle';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useEffect, useRef, useState } from 'react';
import { toAccountNamespace } from '../auth/accountNamespace';
export const favouritesKey = (email?: string | null) =>
  'lgb.favourites.' + toAccountNamespace(email) + '.v1';
const pendingWrites = new Map<string, Promise<void>>();
export const flushFavouriteWrites = (email: string) =>
  pendingWrites.get(favouritesKey(email)) ?? Promise.resolve();
export function useFavourites(email?: string | null) {
  const key = favouritesKey(email);
  const [saved, setSaved] = useState<{ key: string; ids: string[] }>({
    key: '',
    ids: [],
  });
  const [error, setError] = useState('');
  const current = useRef(saved);
  const writes = useRef(Promise.resolve());
  useEffect(
    () =>
      onAccountChange(() => {
        current.current = { key: '', ids: [] };
      }),
    [],
  );
  useEffect(() => {
    let live = true;
    void (pendingWrites.get(key) ?? Promise.resolve())
      .then(() => AsyncStorage.getItem(key))
      .then((raw) => {
        const value: unknown = raw ? JSON.parse(raw) : [];
        if (
          !Array.isArray(value) ||
          !value.every((id) => typeof id === 'string')
        )
          throw new Error('Invalid favourites');
        if (live) {
          const next = { key, ids: value };
          current.current = next;
          setSaved(next);
          setError('');
        }
      })
      .catch(() => {
        if (live) setError('Saved favourites could not be loaded.');
      });
    return () => {
      live = false;
    };
  }, [key]);
  const ready = saved.key === key;
  const ids = ready ? saved.ids : [];
  const toggle = (id: string) => {
    if (!ready || current.current.key !== key) return;
    const previous = current.current.ids;
    const next = {
      key,
      ids: previous.includes(id)
        ? previous.filter((value) => value !== id)
        : [...previous, id],
    };
    current.current = next;
    setSaved(next);
    writes.current = writes.current
      .catch(() => {})
      .then(() => AsyncStorage.setItem(key, JSON.stringify(next.ids)))
      .catch(() => {
        if (current.current.key === key)
          setError('Favourites could not be saved on this device.');
      });
    pendingWrites.set(key, writes.current);
  };
  return { ids, ready, error, toggle };
}
