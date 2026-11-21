import { Platform } from 'react-native';
import Constants from 'expo-constants';

import {
  adaptOriginForRuntime,
  DEFAULT_API_ORIGIN,
  DEFAULT_SOCKET_ORIGIN,
  readConfiguredOrigin,
} from './config';

export interface ApiOrigins {
  api: string;
  asset: string;
  socket: string;
}

export function resolveApiOrigins(
  env: Record<string, string | undefined> = {
    EXPO_PUBLIC_API_ORIGIN: process.env.EXPO_PUBLIC_API_ORIGIN,
    EXPO_PUBLIC_ASSET_ORIGIN: process.env.EXPO_PUBLIC_ASSET_ORIGIN,
    EXPO_PUBLIC_SOCKET_URL: process.env.EXPO_PUBLIC_SOCKET_URL,
    EXPO_PUBLIC_SOCKET_ORIGIN: process.env.EXPO_PUBLIC_SOCKET_ORIGIN,
  },
  runtime = {
    platform: Platform.OS,
    isPhysicalDevice: Constants.isDevice,
  },
): ApiOrigins {
  const api = adaptOriginForRuntime(
    readConfiguredOrigin(env, 'EXPO_PUBLIC_API_ORIGIN', DEFAULT_API_ORIGIN),
    runtime,
  );
  const asset = adaptOriginForRuntime(
    readConfiguredOrigin(env, 'EXPO_PUBLIC_ASSET_ORIGIN', api),
    runtime,
  );
  const configuredSocket =
    env.EXPO_PUBLIC_SOCKET_ORIGIN || env.EXPO_PUBLIC_SOCKET_URL;
  const socket = adaptOriginForRuntime(
    configuredSocket && configuredSocket.trim() !== ''
      ? configuredSocket.trim().replace(/\/$/, '')
      : DEFAULT_SOCKET_ORIGIN,
    runtime,
  );
  if (!__DEV__)
    for (const origin of [api, asset, socket]) {
      const url = new URL(origin);
      if (
        url.protocol !== 'https:' ||
        /^(localhost|127\.|10\.|192\.168\.|\[?::1)/i.test(url.hostname)
      )
        throw new Error('Release builds require public HTTPS origins.');
    }
  return { api, asset, socket };
}
