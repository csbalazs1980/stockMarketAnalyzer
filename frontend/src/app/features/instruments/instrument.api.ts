import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CsvImportEndpoint, CsvImportResult } from '../../core/csv/csv-import';
import { PageResponse } from '../../core/http/page-response';
import { Instrument, InstrumentOption, InstrumentQuery, InstrumentRequest } from './instrument.models';

function toParams(source: Record<string, unknown>): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(source)) {
    if (value !== null && value !== undefined && value !== '') {
      params = params.set(key, String(value));
    }
  }
  return params;
}

/**
 * Instruments are shared reference data. The pickers only read them, and should go through
 * {@link InstrumentStore} rather than calling {@link options} directly: it is what keeps the app
 * from refetching a few thousand rows on every dialog open.
 *
 * The writes (create, update, remove, importCsv) are the administration screen's, and the backend
 * refuses them to anyone without the ADMIN role. Whoever calls one must invalidate the store.
 */
@Injectable({ providedIn: 'root' })
export class InstrumentApi implements CsvImportEndpoint {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/instruments`;

  /**
   * The whole universe in one unpaged response, sorted by ticker.
   *
   * The picker used to ask the paged endpoint for `size=500`, which quietly cut the list off
   * around the C's - Visa was simply not in it. Paging a picker is the bug, not the fix.
   */
  options(activeOnly = true): Observable<InstrumentOption[]> {
    const params = activeOnly ? new HttpParams().set('active', 'true') : new HttpParams();
    return this.http.get<InstrumentOption[]>(`${this.base}/options`, { params });
  }

  /** The paged listing with full rows - what the administration table shows. */
  list(query: InstrumentQuery = {}): Observable<PageResponse<Instrument>> {
    return this.http.get<PageResponse<Instrument>>(this.base, { params: toParams({ ...query }) });
  }

  create(body: InstrumentRequest): Observable<Instrument> {
    return this.http.post<Instrument>(this.base, body);
  }

  /** Addressed by the *current* symbol; the body may carry a new one. */
  update(symbol: string, body: InstrumentRequest): Observable<Instrument> {
    return this.http.put<Instrument>(`${this.base}/${encodeURIComponent(symbol)}`, body);
  }

  remove(symbol: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${encodeURIComponent(symbol)}`);
  }

  importCsv(file: File): Observable<CsvImportResult> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<CsvImportResult>(`${this.base}/import`, form);
  }

  /** Fetched rather than linked to, because the endpoint needs the session cookie. */
  downloadSample(): Observable<Blob> {
    return this.http.get(`${this.base}/import/sample`, { responseType: 'blob' });
  }

  /** Every instrument, in the same columns the import reads. */
  exportCsv(): Observable<Blob> {
    return this.http.get(`${this.base}/export`, { responseType: 'blob' });
  }
}
