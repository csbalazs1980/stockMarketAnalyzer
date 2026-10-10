import { environment } from '../../../environments/environment';

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Matches this application's own API and nothing else - not the i18n JSON files, not any other
 * host. Credentials and the CSRF header are attached on this basis, so the pattern is anchored
 * rather than a `startsWith`.
 */
const API_PATTERN = new RegExp(`^${escapeRegExp(environment.apiBaseUrl)}/api/v1(/.*)?$`, 'i');

const AUTH_PATTERN = new RegExp(`^${escapeRegExp(environment.apiBaseUrl)}/api/v1/auth(/.*)?$`, 'i');

export function isApiUrl(url: string): boolean {
  return API_PATTERN.test(url);
}

/** The sign-in endpoints themselves: never retried, and their 401s are not toasted. */
export function isAuthUrl(url: string): boolean {
  return AUTH_PATTERN.test(url);
}
