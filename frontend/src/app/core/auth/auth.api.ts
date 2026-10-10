import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthSession, LoginCredentials } from './auth.models';

/**
 * The four calls that make up authentication, as far as the browser is concerned.
 *
 * <p>Keycloak is not mentioned here, and no URL in this file points at it. The backend proxies
 * every one of these to the identity provider, which is what lets the SPA present the sign-in
 * as its own: no redirect away from the app, no OIDC callback route, no client id in the page.
 */
@Injectable({ providedIn: 'root' })
export class AuthApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/auth`;

  login(credentials: LoginCredentials): Observable<AuthSession> {
    return this.http.post<AuthSession>(`${this.base}/login`, credentials);
  }

  refresh(): Observable<AuthSession> {
    return this.http.post<AuthSession>(`${this.base}/refresh`, {});
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.base}/logout`, {});
  }

  /**
   * Who is signed in, or `null`. The endpoint answers 204 for an anonymous visitor rather than
   * 401, so a first page load does not have to treat "not signed in yet" as a failure.
   */
  session(): Observable<AuthSession | null> {
    return this.http
      .get<AuthSession>(`${this.base}/session`, { observe: 'response' })
      .pipe(map((response) => response.body ?? null));
  }
}
