import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, finalize, firstValueFrom, Observable, of, shareReplay, tap, throwError } from 'rxjs';

import { AuthApi } from './auth.api';
import { AuthSession, LoginCredentials } from './auth.models';

/**
 * The session, as state the application can render.
 *
 * <p>Deliberately holds no token and schedules no timers. The access cookie is issued with a
 * `Max-Age` matching the token inside it, so the browser stops sending it the moment it expires
 * and the next API call comes back 401 - which `authInterceptor` turns into a refresh and a
 * retry. Renewal is therefore driven by use rather than by a clock, and a tab left open
 * overnight is signed out when the refresh token finally expires, exactly as the old
 * `onInactivityTimeout: 'logout'` arrangement behaved, with nothing to keep in sync.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AuthApi);
  private readonly router = inject(Router);

  private readonly state = signal<AuthSession | null>(null);

  /** A refresh already in flight, shared so that N concurrent 401s spend one refresh token. */
  private inFlightRefresh: Observable<AuthSession> | null = null;

  readonly session = this.state.asReadonly();
  readonly authenticated = computed(() => this.state() !== null);
  readonly displayName = computed(() => this.state()?.displayName ?? '');
  readonly roles = computed<readonly string[]>(() => this.state()?.roles ?? []);
  readonly isAdmin = computed(() => this.roles().includes('ADMIN'));

  /**
   * Restores the session before the first route renders, so guards can answer synchronously and
   * the top bar never flickers from "Sign in" to a username.
   *
   * <p>The 401 branch covers a narrow but real case: an access cookie the browser has not yet
   * dropped but whose token the server will not accept, usually a clock skew away from expiry.
   * The server renews from the refresh cookie by itself in the ordinary case, so this costs a
   * second request only when it has to.
   */
  restore(): Promise<void> {
    return firstValueFrom(
      this.api.session().pipe(
        catchError(() => this.api.refresh().pipe(catchError(() => of(null)))),
        tap((session) => this.state.set(session)),
      ),
    ).then(() => undefined);
  }

  signIn(credentials: LoginCredentials): Observable<AuthSession> {
    return this.api.login(credentials).pipe(tap((session) => this.state.set(session)));
  }

  /**
   * Ends the session at the server - which also revokes it at Keycloak - and then clears it
   * here. The local state is cleared even if the call fails: a sign-out button that leaves the
   * user looking signed in because the network blinked is worse than one that is optimistic.
   */
  signOut(redirectTo: string = '/'): void {
    this.api.logout().subscribe({
      next: () => this.finishSignOut(redirectTo),
      error: () => this.finishSignOut(redirectTo),
    });
  }

  /**
   * Spends the refresh cookie. Concurrent callers share one request: without this, a page that
   * fires five requests on load would answer five 401s with five refreshes, and Keycloak rotates
   * the refresh token on each - so four of them would be spending a token already replaced.
   */
  refresh(): Observable<AuthSession> {
    if (!this.inFlightRefresh) {
      this.inFlightRefresh = this.api.refresh().pipe(
        tap((session) => this.state.set(session)),
        catchError((error: unknown) => {
          this.state.set(null);
          return throwError(() => error);
        }),
        finalize(() => (this.inFlightRefresh = null)),
        shareReplay({ bufferSize: 1, refCount: true }),
      );
    }
    return this.inFlightRefresh;
  }

  /** Called by the interceptor once a refresh has failed: the session is over, ask again. */
  sessionExpired(): void {
    this.state.set(null);
    void this.router.navigate(['/login'], {
      queryParams: { returnUrl: this.router.url, expired: 1 },
    });
  }

  /** Sends the visitor to the application's own login page, and back where they were after. */
  goToLogin(returnUrl: string = this.router.url): void {
    void this.router.navigate(['/login'], { queryParams: { returnUrl } });
  }

  private finishSignOut(redirectTo: string): void {
    this.state.set(null);
    void this.router.navigateByUrl(redirectTo);
  }
}
