/**
 * Lives in its own file on purpose: angular.json's fileReplacements swaps
 * environment.ts for environment.development.ts, so a shared type declared in
 * environment.ts would be imported by the very file that replaces it.
 */
export interface AppEnvironment {
  readonly production: boolean;
  /** Origin of the backend, no trailing slash. '' means same-origin. */
  readonly apiBaseUrl: string;
}

/*
 * There is deliberately no Keycloak block here any more. The browser does not know the identity
 * provider exists: it posts credentials to this application's own /api/v1/auth/login, and the
 * backend holds the realm, the client id and the client secret.
 */
