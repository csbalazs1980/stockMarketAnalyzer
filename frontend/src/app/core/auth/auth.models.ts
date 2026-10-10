/**
 * Everything the frontend is told about the signed-in user.
 *
 * <p>There is no token here, and that is the point: the access and refresh tokens live in
 * `HttpOnly` cookies the browser attaches by itself, so no code in this application - and no
 * script injected into it - can read them.
 */
export interface AuthSession {
  readonly username: string;
  readonly displayName: string;
  readonly roles: readonly string[];
  /** Seconds the access token had left when the server answered. Informational. */
  readonly expiresInSeconds: number;
}

export interface LoginCredentials {
  readonly username: string;
  readonly password: string;
}
