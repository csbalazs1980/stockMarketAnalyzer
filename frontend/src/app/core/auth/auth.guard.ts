import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Sends anonymous visitors to the application's own login page instead of to Keycloak.
 *
 * <p>Synchronous, and allowed to be: an app initializer restores the session before the router
 * runs, so by the time a guard is asked, the answer is already known. Returning a `UrlTree`
 * rather than navigating keeps the failed URL out of the history, so the browser's back button
 * after signing in does not land on the login page again.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return (
    auth.authenticated() ||
    router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } })
  );
};
