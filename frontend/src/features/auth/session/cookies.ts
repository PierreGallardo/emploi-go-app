/** Over HTTPS the cookies carry the `__Host-` prefix, which no sibling subdomain can set; plain HTTP (`local` profile) has none. */
export function cookieNames(protocol: string) {
  const prefix = protocol === 'https:' ? '__Host-' : '';
  return {
    /** CSRF token, sent back in the `X-CSRF-TOKEN` header. */
    csrf: `${prefix}XSRF-TOKEN`,
    /** Present when an automatic reconnection is possible: no secret, the API owns its lifetime. */
    reconnectable: `${prefix}EMPLOIGO_RECONNECTABLE`,
  } as const;
}

export function readCookie(name: string, cookies: string): string | undefined {
  for (const pair of cookies.split(';')) {
    const separator = pair.indexOf('=');
    // Only the ASCII spaces that follow `;`: a name padded with other white space is another cookie.
    const candidate = pair.slice(0, separator).replace(/^ +/, '');
    if (separator > 0 && candidate === name) return pair.slice(separator + 1);
  }
  return undefined;
}

/** Present when an automatic reconnection is possible: the only sign of an account readable before the session check. */
export function hasReconnectionIndicator() {
  return !!browserCookie('reconnectable');
}

export function browserCookie(name: keyof ReturnType<typeof cookieNames>) {
  return readCookie(
    cookieNames(window.location.protocol)[name],
    document.cookie,
  );
}
