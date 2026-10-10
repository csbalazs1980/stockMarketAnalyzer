import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { MessageService } from 'primeng/api';
import { catchError, throwError } from 'rxjs';

import { isAuthUrl } from '../auth/api-url';
import { problemMessage } from './problem-detail';

/**
 * Surfaces backend failures as toasts.
 *
 * Two deliberate exceptions. 409 is passed through untouched: the entries page turns it into
 * the "delete the subtree too?" prompt itself. And a 401 from the auth endpoints is the normal
 * way those endpoints say "not signed in" or "wrong password" - the login page shows that
 * inline, and a page load that finds no session is not an error worth interrupting anyone over.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const messages = inject(MessageService);
  const transloco = inject(TranslocoService);

  return next(req).pipe(
    catchError((error: unknown) => {
      const silent =
        error instanceof HttpErrorResponse &&
        (error.status === 409 || (error.status === 401 && isAuthUrl(req.url)));

      if (error instanceof HttpErrorResponse && !silent) {
        const summary =
          error.status === 0
            ? transloco.translate('errors.network')
            : transloco.translate('errors.requestFailed', { status: error.status });

        messages.add({
          severity: 'error',
          summary,
          detail: problemMessage(error, transloco.translate('errors.unknown')),
          life: 6000,
        });
      }
      return throwError(() => error);
    }),
  );
};
