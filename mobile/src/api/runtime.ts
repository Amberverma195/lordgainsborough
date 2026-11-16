import { Platform } from 'react-native';
import Constants from 'expo-constants';

import { adaptOriginForRuntime, DEFAULT_API_ORIGIN, DEFAULT_SOCKET_ORIGIN, readConfiguredOrigin } from './config';

export interface ApiOrigins {
  api: string;
  asset: string;
  socket: string;
}

export function resolveApiOrigins(
  env: Record<string, string | undefined> = process.env,
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
  const configuredSocket = env.EXPO_PUBLIC_SOCKET_URL || env.EXPO_PUBLIC_SOCKET_ORIGIN;
  const socket = adaptOriginForRuntime(
    configuredSocket && configuredSocket.trim() !== ''
      ? configuredSocket.trim().replace(/\/$/, '')
      : DEFAULT_SOCKET_ORIGIN,
    runtime,
  );
  return { api, asset, socket };
}
