import { renderHook, act, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useFavourites, favouritesKey } from '../useFavourites';
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);
beforeEach(async () => {
  await AsyncStorage.clear();
});
it('persists rapid toggles and isolates account switches', async () => {
  const hook = await renderHook<
    ReturnType<typeof useFavourites>,
    { email: string }
  >(({ email }) => useFavourites(email), {
    initialProps: { email: 'a@example.com' },
  });
  await waitFor(() => expect(hook.result.current.ready).toBe(true));
  await act(async () => {
    hook.result.current.toggle('one');
    hook.result.current.toggle('two');
  });
  await waitFor(async () =>
    expect(
      JSON.parse((await AsyncStorage.getItem(favouritesKey('a@example.com')))!),
    ).toEqual(['one', 'two']),
  );
  await hook.rerender({ email: 'b@example.com' });
  await waitFor(() => expect(hook.result.current.ready).toBe(true));
  expect(hook.result.current.ids).toEqual([]);
  await hook.rerender({ email: 'a@example.com' });
  await waitFor(() => expect(hook.result.current.ids).toEqual(['one', 'two']));
});
