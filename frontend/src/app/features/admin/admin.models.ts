/**
 * The realm's users as this application's API reports them.
 *
 * <p>Keycloak is not mentioned, and no field here is Keycloak-shaped beyond `id`, which is the
 * subject the backend keys wealth entries and dividends on. The browser never talks to the
 * identity provider - it talks to `/api/v1/admin`, and the service account that reaches Keycloak
 * stays on the server.
 *
 * <p>Optional rather than nullable: the backend serialises with `non_null` inclusion, so an
 * absent email is a missing key, not a `null`.
 */
export interface AdminUser {
  readonly id: string;
  readonly username: string;
  readonly email?: string;
  readonly firstName?: string;
  readonly lastName?: string;
  readonly enabled: boolean;
  readonly roles: readonly string[];
  readonly createdAt?: string;
}

/** An assignable privilege, read from the realm rather than hard-coded in the bundle. */
export interface RealmRole {
  readonly name: string;
  readonly description?: string;
}

export interface CreateUserRequest {
  readonly username: string;
  readonly email: string | null;
  readonly firstName: string | null;
  readonly lastName: string | null;
  readonly password: string;
  /** Keycloak forces a change at first sign-in, so the password the administrator knows expires. */
  readonly temporaryPassword: boolean;
  readonly roles: readonly string[];
}

export interface ResetPasswordRequest {
  readonly password: string;
  readonly temporary: boolean;
}

export interface AdminUserQuery {
  readonly search?: string | null;
  readonly page?: number;
  readonly size?: number;
}
