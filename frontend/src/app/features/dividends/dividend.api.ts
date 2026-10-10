import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, mergeMap, Observable, of, range, switchMap, toArray } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CsvImportEndpoint, CsvImportResult } from '../../core/csv/csv-import';
import { PageResponse } from '../../core/http/page-response';
import { Dividend, DividendQuery, DividendRequest, DividendSummary } from './dividend.models';

/** Rows per request when reading a whole range. Well under Spring's 2000-row page ceiling. */
const PAGE_SIZE = 500;

/** How many follow-up pages are in flight at once. */
const MAX_IN_FLIGHT = 4;

function toParams(source: Record<string, unknown>): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(source)) {
    if (value !== null && value !== undefined && value !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}

@Injectable({ providedIn: 'root' })
export class DividendApi implements CsvImportEndpoint {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/dividends`;

  list(query: DividendQuery = {}): Observable<PageResponse<Dividend>> {
    return this.http.get<PageResponse<Dividend>>(this.base, { params: toParams({ ...query }) });
  }

  /**
   * Every matching row, not a page of them.
   *
   * The monthly grid pivots the whole range client-side, so a page boundary would silently punch
   * a hole in a month. The first page also reports how many there are, so the rest go out at once
   * rather than one round trip per page.
   */
  listAll(query: DividendQuery = {}, pageSize = PAGE_SIZE): Observable<Dividend[]> {
    return this.list({ ...query, page: 0, size: pageSize }).pipe(
      switchMap((first) => {
        if (first.totalPages <= 1) {
          return of(first.content);
        }
        return range(1, first.totalPages - 1).pipe(
          mergeMap((index) => this.list({ ...query, page: index, size: pageSize }), MAX_IN_FLIGHT),
          toArray(),
          map((pages) => [
            ...first.content,
            ...pages.sort((a, b) => a.page - b.page).flatMap((page) => page.content),
          ]),
        );
      }),
    );
  }

  /**
   * The same filters as list(), summed over everything they match. Asked for separately so the
   * figure stays put while you page - a total that only covered the visible rows would be worse
   * than showing none at all.
   */
  summary(query: DividendQuery = {}): Observable<DividendSummary> {
    const { page: _page, size: _size, sort: _sort, ...filters } = query;
    return this.http.get<DividendSummary>(`${this.base}/summary`, {
      params: toParams({ ...filters }),
    });
  }

  create(body: DividendRequest): Observable<Dividend> {
    return this.http.post<Dividend>(this.base, body);
  }

  update(id: number, body: DividendRequest): Observable<Dividend> {
    return this.http.put<Dividend>(`${this.base}/${id}`, body);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  /**
   * Uploads a whole file of payments. No Content-Type is set on purpose: the browser has to
   * write it itself so the multipart boundary matches the body it generated.
   */
  importCsv(file: File): Observable<CsvImportResult> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<CsvImportResult>(`${this.base}/import`, form);
  }

  /** Fetched rather than linked to, because the endpoint needs the bearer token. */
  downloadSample(): Observable<Blob> {
    return this.http.get(`${this.base}/import/sample`, { responseType: 'blob' });
  }

  /** The caller's own rows, in the same columns the import reads. Fetched for the same reason. */
  exportCsv(): Observable<Blob> {
    return this.http.get(`${this.base}/export`, { responseType: 'blob' });
  }
}
