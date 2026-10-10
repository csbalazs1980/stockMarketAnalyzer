import { inject, Injectable } from '@angular/core';
import { catchError, Observable, shareReplay, throwError } from 'rxjs';

import { InstrumentApi } from './instrument.api';
import { InstrumentOption } from './instrument.models';

/**
 * How long a fetched list is served before the next reader goes back to the server.
 *
 * The universe changes when somebody edits reference data, which is rare and never urgent, so a
 * bounded staleness beats both refetching per dialog and caching until the tab closes.
 */
const TTL_MS = 30 * 60 * 1000;

interface CacheEntry {
  /** When the request was *started*, so a slow response cannot extend its own lifetime. */
  readonly startedAt: number;
  readonly options$: Observable<InstrumentOption[]>;
}

/**
 * The single place the app reads the instrument universe from.
 *
 * `shareReplay` means concurrent callers - the filter bar and the dialog on the same page, or two
 * features at once - share one in-flight request and then one cached array. Failures are not
 * cached: the entry is dropped so the next reader retries rather than inheriting an error for
 * half an hour.
 */
@Injectable({ providedIn: 'root' })
export class InstrumentStore {
  private readonly api = inject(InstrumentApi);

  private cache: CacheEntry | null = null;

  /** Active instruments, sorted by ticker. Cheap to call: hits the network at most once per TTL. */
  options(): Observable<InstrumentOption[]> {
    const startedAt = Date.now();
    if (this.cache && startedAt - this.cache.startedAt < TTL_MS) {
      return this.cache.options$;
    }

    const options$ = this.api.options().pipe(
      catchError((error: unknown) => {
        this.invalidate();
        return throwError(() => error);
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );

    this.cache = { startedAt, options$ };
    return options$;
  }

  /**
   * Drop the cached list. The instrument administration page calls this after every create,
   * update, delete and import, so a new ticker is in the pickers on the next dialog open.
   */
  invalidate(): void {
    this.cache = null;
  }
}
