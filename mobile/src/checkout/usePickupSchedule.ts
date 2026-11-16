import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AppState, type AppStateStatus } from 'react-native';

import {
  isPickupDateValid,
  isPickupTimeValid,
  pickupChoices,
  pickupDateChoices,
} from './pickup';

export interface UsePickupScheduleOptions {
  selectedDate?: string;
  selectedTime?: string;
  onInvalidateTime?: (expiredTime: string) => void;
  onInvalidateDate?: (expiredDate: string) => void;
  refreshIntervalMs?: number;
  getNow?: () => Date;
  enabled?: boolean;
}

export interface UsePickupScheduleResult {
  referenceTime: Date;
  dates: string[];
  times: string[];
  refresh: () => void;
  isDateValid: boolean;
  isTimeValid: boolean;
}

export function usePickupSchedule({
  selectedDate,
  selectedTime,
  onInvalidateTime,
  onInvalidateDate,
  refreshIntervalMs = 30_000,
  getNow = () => new Date(),
  enabled = true,
}: UsePickupScheduleOptions = {}): UsePickupScheduleResult {
  const getNowRef = useRef(getNow);
  const onInvalidateTimeRef = useRef(onInvalidateTime);
  const onInvalidateDateRef = useRef(onInvalidateDate);

  useEffect(() => {
    getNowRef.current = getNow;
    onInvalidateTimeRef.current = onInvalidateTime;
    onInvalidateDateRef.current = onInvalidateDate;
  });

  const [referenceTime, setReferenceTime] = useState<Date>(getNow);

  const refresh = useCallback(() => {
    setReferenceTime(getNowRef.current());
  }, []);

  // Periodic clock refresh as time passes
  useEffect(() => {
    if (!enabled || refreshIntervalMs <= 0) {
      return;
    }
    const timer = setInterval(() => {
      setReferenceTime(getNowRef.current());
    }, refreshIntervalMs);

    return () => clearInterval(timer);
  }, [enabled, refreshIntervalMs]);

  // Regenerate available slots when app resumes from background
  useEffect(() => {
    if (!enabled) {
      return;
    }
    const subscription = AppState.addEventListener('change', (status: AppStateStatus) => {
      if (status === 'active') {
        setReferenceTime(getNowRef.current());
      }
    });

    return () => subscription.remove();
  }, [enabled]);

  // Re-generate available date and time choices based on the reference clock
  const dates = useMemo(() => pickupDateChoices(referenceTime), [referenceTime]);

  const times = useMemo(
    () => (selectedDate ? pickupChoices(selectedDate, referenceTime) : []),
    [selectedDate, referenceTime],
  );

  const isDateValid = useMemo(
    () => (selectedDate ? isPickupDateValid(selectedDate, referenceTime) : true),
    [selectedDate, referenceTime],
  );

  const isTimeValid = useMemo(
    () => (selectedDate && selectedTime ? isPickupTimeValid(selectedDate, selectedTime, referenceTime) : true),
    [selectedDate, selectedTime, referenceTime],
  );

  const lastInvalidatedDateRef = useRef<string | null>(null);
  const lastInvalidatedTimeRef = useRef<string | null>(null);

  // Invalidate any selected date or time that is no longer available
  useEffect(() => {
    if (!enabled) {
      return;
    }

    if (selectedDate && !isPickupDateValid(selectedDate, referenceTime)) {
      if (lastInvalidatedDateRef.current !== selectedDate) {
        lastInvalidatedDateRef.current = selectedDate;
        onInvalidateDateRef.current?.(selectedDate);
      }
    } else {
      lastInvalidatedDateRef.current = null;

      if (selectedDate && selectedTime) {
        const key = `${selectedDate}:${selectedTime}`;
        if (!isPickupTimeValid(selectedDate, selectedTime, referenceTime)) {
          if (lastInvalidatedTimeRef.current !== key) {
            lastInvalidatedTimeRef.current = key;
            onInvalidateTimeRef.current?.(selectedTime);
          }
        } else {
          lastInvalidatedTimeRef.current = null;
        }
      } else {
        lastInvalidatedTimeRef.current = null;
      }
    }
  }, [selectedDate, selectedTime, referenceTime, enabled]);

  return {
    referenceTime,
    dates,
    times,
    refresh,
    isDateValid,
    isTimeValid,
  };
}
