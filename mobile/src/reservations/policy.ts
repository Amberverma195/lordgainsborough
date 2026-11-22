import {apiRequest} from '../api/client';
import {SeatingPolicy} from './choices';
export async function fetchSeatingPolicy():Promise<SeatingPolicy>{const response=await apiRequest('/api/reservation-policy');const value=response.body as SeatingPolicy;if(!value||!Number.isInteger(value.lastSeatingMinutes)||value.lastSeatingMinutes<1||!Number.isInteger(value.advanceNoticeMinutes)||value.advanceNoticeMinutes<1)throw new Error('Reservation policy could not be loaded.');return value;}
