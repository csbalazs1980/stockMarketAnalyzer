import { HttpErrorResponse, HttpInterceptorFn, HttpXsrfTokenExtractor } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';

import { isApiUrl, isAuthUrl } from './api-url';
import { AuthService } from './auth.service';

const XSRF_HEADER = 'X-XSRF-TOKEN';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/**
 * Makes cookie authentication work, and hides the fact that it is happening.
 *
 * <p>Three jobs, all of them consequences of the tokens living in `HttpOnly` cookies rather than
 * in a variable this code could reach:
 *
 * 1. `withCredentials`, because a cross-origin call (`localhost:4200` to `localhost:8080` in
 *    development) does not send cookies otherwise.
 * 2. The CSRF header. Cookies ride along on any request the browser can be talked into making,
 *    so the backend demands a value only a page on an allowed origin could have read - the
 *    `XSRF-TOKEN` cookie, echoed back in a header. Angular's own XSRF interceptor does this only
 *    for same-origin URLs, so in development it would silently do nothing.
 * 3. Refresh-and-retry. A 401 means the access token has expired; one refresh, then the original
 *    request runs again. `AuthService.refresh()` shares a single request between concurrent
 *    callers, so a page that fires several requests at once spends one refresh token, not five.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isApiUrl(req.url)) {
    return next(req);
  }

  const auth = inject(AuthService);
  const xsrf = inject(HttpXsrfTokenExtractor);

  const send = () => {
    let request = req.clone({ withCredentials: true });
    if (!SAFE_METHODS.has(request.method) && !request.headers.has(XSRF_HEADER)) {
      const token = xsrf.getToken();
      if (token) {
        request = request.clone({ setHeaders: { [XSRF_HEADER]: token } });
      }
    }
    return request;
  };

  // Refreshing a refresh is a loop, and a failed sign-in is not a stale session.
  if (isAuthUrl(req.url)) {
    return next(send());
  }

  return next(send()).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        // Re-read the CSRF token: the refresh response may have rotated it.
        switchMap(() => next(send())),
        catchError(() => {
          auth.sessionExpired();
          // The original 401 is what the caller asked about; the refresh failure is our business.
          return throwError(() => error);
        }),
      );
    }),
  );
};
