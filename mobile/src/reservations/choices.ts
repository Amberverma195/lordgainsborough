import { restaurantNow } from '../checkout/pickup';
import { toAccountNamespace } from '../auth/accountNamespace';
export function reservationKeys(email?: string | null) {
  const scope = toAccountNamespace(email);
  return {
    pending: 'lgb.reservation.pending.' + scope + '.v2',
    reference: 'lgb.reservation.reference.' + scope + '.v2',
  };
}
export function reservationDates(now = new Date()) {
  const today = restaurantNow(now).date;
  const first = new Date(today + 'T12:00:00Z');
  const last = new Date(first);
  last.setUTCFullYear(last.getUTCFullYear() + 1);
  const result: string[] = [];
  for (
    const cursor = new Date(first);
    cursor <= last;
    cursor.setUTCDate(cursor.getUTCDate() + 1)
  )
    result.push(cursor.toISOString().slice(0, 10));
  return result;
}
export function reservationTimes(date: string, now = new Date(), policy:SeatingPolicy=proposedSeatingPolicy) {
  if (!reservationDates(now).includes(date)) return [];
  const today = restaurantNow(now);
  const day = new Date(date + 'T12:00:00Z').getUTCDay();
  const open = day === 0 ? 600 : 540;
  const close = day === 0 ? 1080 : day === 5 || day === 6 ? 1230 : 1200;
  const times: string[] = [];
  const isApproved = Boolean(policy?.managementApproved);
  const lastSeating = isApproved ? policy.lastSeatingMinutes : 30;
  const notice = isApproved ? policy.advanceNoticeMinutes : 0;
  for (let minute = open; minute < close && minute <= close - lastSeating; minute += 30)
    if (
      (date !== today.date || minute > today.minutes) &&
      (!isApproved ||
        torontoSeatingInstant(
          date,
          String(Math.floor(minute / 60)).padStart(2, '0') +
            ':' +
            String(minute % 60).padStart(2, '0'),
        ) >=
          now.getTime() + notice * 60000)
    )
      times.push(
        String(Math.floor(minute / 60)).padStart(2, '0') +
          ':' +
          String(minute % 60).padStart(2, '0'),
      );
  return times;
}

export interface SeatingPolicy {lastSeatingMinutes:number;advanceNoticeMinutes:number;managementApproved?:boolean;}
export const proposedSeatingPolicy:SeatingPolicy={lastSeatingMinutes:60,advanceNoticeMinutes:30,managementApproved:true};
export function torontoSeatingInstant(date:string,time:string):number {
 const wanted=Date.parse(date+'T'+time+':00Z');let instant=wanted;
 for(let i=0;i<3;i++){const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'America/Toronto',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(new Date(instant));const read=(type:string)=>Number(parts.find(part=>part.type===type)?.value);instant+=wanted-Date.UTC(read('year'),read('month')-1,read('day'),read('hour'),read('minute'));}
 return instant;
}
