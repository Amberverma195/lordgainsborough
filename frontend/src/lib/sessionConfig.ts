/** Host-only production cookies prevent sibling domains from injecting session/CSRF cookies. */
export function sessionCookieConfig(url: URL) {
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  const secure = url.protocol === 'https:' || !loopback;
  return {
    secure,
    session: secure ? '__Host-lgb_session' : 'lgb_session',
    csrf: secure ? '__Host-lgb_csrf' : 'lgb_csrf',
  };
}
