import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CsvImportEndpoint, CsvImportResult } from '../../core/csv/csv-import';
import { PageResponse } from '../../core/http/page-response';
import {
  InflationFetchResponse,
  InflationRate,
  InflationRateQuery,
  InflationRateRequest,
} from './inflation.models';

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
 * Inflation figures are shared reference data. Any signed-in user can read them; the writes
 * (create, update, remove, importCsv) belong to the administration screen and the backend refuses
 * them to anyone without the ADMIN role.
 *
 * Lives in its own feature folder rather than under admin/ because the screens that will *use*
 * the figures (wealth, dividends) only need the reads.
 */
@Injectable({ providedIn: 'root' })
export class InflationApi implements CsvImportEndpoint {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/inflation-rates`;

  list(query: InflationRateQuery = {}): Observable<PageResponse<InflationRate>> {
    return this.http.get<PageResponse<InflationRate>>(this.base, { params: toParams({ ...query }) });
  }

  /** Every currency with at least one stored figure, alphabetically. */
  units(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/units`);
  }

  /**
   * An upsert on (currency, year): a figure already stored for the pair is replaced, which is how
   * a provisional estimate gets superseded by the final print.
   */
  create(body: InflationRateRequest): Observable<InflationRate> {
    return this.http.post<InflationRate>(this.base, body);
  }

  /** By id, so the currency and year can be corrected too. 409 when the target pair is taken. */
  update(id: number, body: InflationRateRequest): Observable<InflationRate> {
    return this.http.put<InflationRate>(`${this.base}/${id}`, body);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  /**
   * Reads HUF, EUR and USD from Eurostat on the server and stores only the years after the latest
   * one already held per currency (all of them for a currency with none). Never overwrites.
   * ADMIN only; a 502 means the source could not be used and nothing was stored.
   */
  fetchLatest(): Observable<InflationFetchResponse> {
    return this.http.post<InflationFetchResponse>(`${this.base}/fetch`, null);
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

  /** Every stored figure, in the same columns the import reads. */
  exportCsv(): Observable<Blob> {
    return this.http.get(`${this.base}/export`, { responseType: 'blob' });
  }
}
