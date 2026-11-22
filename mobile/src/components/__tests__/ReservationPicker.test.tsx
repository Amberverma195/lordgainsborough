import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react-native';
import { ReservationPicker } from '../ReservationPicker';
import { reservationDates, reservationTimes } from '../../reservations/choices';
it('uses native modal date selection and resets the previous time', async () => {
  const date = reservationDates()[1];
  const changed = jest.fn();
  await render(
    <ReservationPicker
      date=""
      time="18:00"
      partySize="2"
      disabled={false}
      onChange={changed}
    />,
  );
  await fireEvent.press(screen.getByLabelText('Choose reservation date'));
  await fireEvent.press(screen.getByLabelText('Select ' + date));
  expect(changed).toHaveBeenCalledWith({ date, time: '', partySize: '2' });
});
it('selects an offered Toronto time and bounded party size', async () => {
  const date = reservationDates()[1];
  const time = reservationTimes(date)[0];
  const changed = jest.fn();
  await render(
    <ReservationPicker
      date={date}
      time=""
      partySize="2"
      disabled={false}
      onChange={changed}
    />,
  );
  await fireEvent.press(screen.getByLabelText('Choose reservation time'));
  await fireEvent.press(screen.getByLabelText('Select ' + time));
  expect(changed).toHaveBeenCalledWith({ date, time, partySize: '2' });
  await fireEvent.press(screen.getByLabelText('Choose reservation partySize'));
  await fireEvent.press(screen.getByLabelText('Select 4'));
  expect(changed).toHaveBeenCalledWith({ date, time: '', partySize: '4' });
});

it('selects a time slot using the passed policy rather than default proposed policy', async () => {
  const futureDates = reservationDates();
  const date = futureDates.find((d) => {
    const day = new Date(d + 'T12:00:00Z').getUTCDay();
    return day >= 1 && day <= 4;
  })!;
  const customPolicy = {
    lastSeatingMinutes: 30,
    advanceNoticeMinutes: 0,
    managementApproved: true,
  };
  const changed = jest.fn();
  await render(
    <ReservationPicker
      date={date}
      time=""
      partySize="2"
      disabled={false}
      onChange={changed}
      policy={customPolicy}
    />,
  );
  await fireEvent.press(screen.getByLabelText('Choose reservation time'));
  await fireEvent.press(screen.getByLabelText('Select 19:30'));
  expect(changed).toHaveBeenCalledWith({ date, time: '19:30', partySize: '2' });
});
