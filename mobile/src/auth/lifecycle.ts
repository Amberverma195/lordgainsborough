const listeners = new Set<() => void>();
export function onAccountChange(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
export function accountWillChange() {
  listeners.forEach((listener) => listener());
}
