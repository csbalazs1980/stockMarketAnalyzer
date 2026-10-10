import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Keeps the administration screens to administrators.
 *
 * <p>Two different answers for two different situations, which is the whole point of not
 * folding this into `authGuard`. A visitor who is not signed in is sent to the login page with
 * a `returnUrl`, because signing in may well be all that is missing. A signed-in USER is sent
 * home instead: they are already who they are, and bouncing them to a login form they have
 * already filled in would suggest otherwise.
 *
 * <p>This is an affordance, not the protection. The API refuses `/api/v1/admin/**` to anyone
 * without the ADMIN realm role, and nothing the browser believes about itself changes that.
 */
export const adminGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.authenticated()) {
    return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  }
  return auth.isAdmin() || router.createUrlTree(['/']);
};
