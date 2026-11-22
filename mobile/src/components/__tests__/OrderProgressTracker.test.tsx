import { act, fireEvent, render } from '@testing-library/react-native';
import React from 'react';

import { OrderProgressTracker } from '../OrderProgressTracker';

describe('OrderProgressTracker component', () => {
  it('renders order progress with active and waiting steps for NEW order', async () => {
    const { getByText } = await render(
      <OrderProgressTracker
        status="NEW"
        createdAt="2026-11-22T02:02:00Z"
      />,
    );

    expect(getByText('Order Progress')).toBeTruthy();
    expect(getByText('Order Placed')).toBeTruthy();
    expect(getByText('In Kitchen')).toBeTruthy();
    expect(getByText('Ready for Pickup')).toBeTruthy();
    expect(getByText('Picked Up')).toBeTruthy();
    expect(getByText('Step 1 of 4')).toBeTruthy();
  });

  it('renders completed checkmarks and timestamps for ACCEPTED status', async () => {
    const { getByText } = await render(
      <OrderProgressTracker
        status="ACCEPTED"
        createdAt="2026-11-22T02:02:00Z"
        acceptedAt="2026-11-22T02:10:00Z"
      />,
    );

    expect(getByText('Step 2 of 4')).toBeTruthy();
    expect(getByText('✓')).toBeTruthy();
  });

  it('renders no-show alert banner when order is marked as NO_SHOW', async () => {
    const { getByText } = await render(
      <OrderProgressTracker
        status="NO_SHOW"
        createdAt="2026-11-22T02:02:00Z"
        noShowAt="2026-11-22T03:00:00Z"
      />,
    );

    expect(getByText('Order Marked as No-Show')).toBeTruthy();
    expect(getByText(/marked as a no-show by the kitchen/i)).toBeTruthy();
    expect(getByText(/\(519\) 472-4168/)).toBeTruthy();
  });

  it('toggles activity history accordion and reveals milestones', async () => {
    const { getByText, getByLabelText } = await render(
      <OrderProgressTracker
        status="READY"
        createdAt="2026-11-22T02:02:00Z"
        acceptedAt="2026-11-22T02:10:00Z"
        readyAt="2026-11-22T02:25:00Z"
      />,
    );

    const toggle = getByLabelText('Toggle activity history');
    await act(async () => {
      fireEvent.press(toggle);
    });

    expect(getByText('Order placed')).toBeTruthy();
    expect(getByText('Kitchen accepted')).toBeTruthy();
    expect(getByText('Ready for pickup')).toBeTruthy();
  });
});
