module.exports = ({ config }) => {
  const release =
    process.env.EAS_BUILD_PROFILE &&
    process.env.EAS_BUILD_PROFILE !== 'development';
  if (release) {
    for (const key of [
      'EXPO_PUBLIC_API_ORIGIN',
      'EXPO_PUBLIC_SOCKET_ORIGIN',
      'EXPO_PUBLIC_ASSET_ORIGIN',
    ]) {
      if (!process.env[key]) throw new Error(key + ' is required');
      const url = new URL(process.env[key]);
      if (
        url.protocol !== 'https:' ||
        /^(localhost|127\.|10\.|192\.168\.|\[?::1)/i.test(url.hostname) ||
        url.username ||
        url.password
      )
        throw new Error(key + ' must be public HTTPS');
    }
    if (!process.env.EXPO_PROJECT_ID)
      throw new Error('Set EXPO_PROJECT_ID to the existing Expo project UUID');
  }
  return {
    ...config,
    ...(process.env.EXPO_OWNER ? { owner: process.env.EXPO_OWNER } : {}),
    extra: {
      ...config.extra,
      ...(process.env.EXPO_PROJECT_ID
        ? { eas: { projectId: process.env.EXPO_PROJECT_ID } }
        : {}),
    },
    ios: {
      ...config.ios,
      infoPlist: {
        ...config.ios?.infoPlist,
        ITSAppUsesNonExemptEncryption: false,
      },
    },
  };
};
