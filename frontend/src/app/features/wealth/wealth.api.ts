import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { TreeNode } from 'primeng/api';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CsvImportEndpoint, CsvImportResult } from '../../core/csv/csv-import';
import { PageResponse } from '../../core/http/page-response';
import {
  ExchangeRate,
  ExchangeRateQuery,
  ExchangeRateRequest,
  WealthEntry,
  WealthEntryCloneRequest,
  RateRefreshResult,
  WealthEntryQuery,
  WealthEntryRequest,
} from './wealth.models';

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
export class WealthEntryApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/wealth/entries`;

  list(query: WealthEntryQuery = {}): Observable<PageResponse<WealthEntry>> {
    return this.http.get<PageResponse<WealthEntry>>(this.base, {
      params: toParams({ ...query }),
    });
  }

  create(body: WealthEntryRequest): Observable<WealthEntry> {
    return this.http.post<WealthEntry>(this.base, body);
  }

  /**
   * Copies the entry and everything under it into a new snapshot. One request rather than a
   * create per row: the backend writes the whole subtree in a transaction, so a failure leaves
   * nothing behind instead of half a month.
   */
  cloneSubtree(id: number, body: WealthEntryCloneRequest): Observable<WealthEntry[]> {
    return this.http.post<WealthEntry[]>(`${this.base}/${id}/clone`, body);
  }

  update(id: number, body: WealthEntryRequest): Observable<WealthEntry> {
    return this.http.put<WealthEntry>(`${this.base}/${id}`, body);
  }

  remove(id: number, cascade = false): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`, {
      params: toParams({ cascade }),
    });
  }
}

@Injectable({ providedIn: 'root' })
export class ExchangeRateApi implements CsvImportEndpoint {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/wealth/exchange-rates`;

  list(query: ExchangeRateQuery = {}): Observable<PageResponse<ExchangeRate>> {
    return this.http.get<PageResponse<ExchangeRate>>(this.base, {
      params: toParams({ ...query }),
    });
  }

  /**
   * The newest quote per pair, whatever its date - one small response rather than the whole rate
   * history, which grows by a row per pair per day once the central-bank feed is running.
   *
   * `/in-force` answers the same question for one pair on one date. This is what a figure carrying
   * no date of its own - a recurring expense, say - has to be converted with, so it is deliberately
   * date-less rather than defaulting to today.
   */
  latest(): Observable<ExchangeRate[]> {
    return this.http.get<ExchangeRate[]>(`${this.base}/latest`);
  }

  /** POST is an upsert: the same pair and date replaces the stored quote. */
  upsert(body: ExchangeRateRequest): Observable<ExchangeRate> {
    return this.http.post<ExchangeRate>(this.base, body);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  /**
   * Uploads a whole file of quotes. No Content-Type is set on purpose: the browser has to write
   * it itself so the multipart boundary matches the body it generated.
   */
  importCsv(file: File, extras?: Record<string, string>): Observable<CsvImportResult> {
    const form = new FormData();
    form.append('file', file);
    // 'global' for an administrator storing the whole file as shared rates.
    for (const [key, value] of Object.entries(extras ?? {})) {
      form.append(key, value);
    }
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

  /**
   * Asks the backend to read today's published rates and store them.
   *
   * The fetch happens server-side rather than from here: the browser would be blocked by the
   * feed's CORS policy, and routing it through the API keeps the choice of source in one place
   * instead of baking a third party's URL into the bundle.
   */
  refresh(): Observable<RateRefreshResult> {
    return this.http.post<RateRefreshResult>(`${this.base}/refresh`, {});
  }
}

/**
 * The API returns a flat page of rows carrying parentId. An entry whose parent is
 * not in the same page (filtered out, or on another page) surfaces as a root so
 * nothing silently disappears from the table.
 */
export function buildEntryTree(entries: readonly WealthEntry[]): TreeNode<WealthEntry>[] {
  const byId = new Map<number, TreeNode<WealthEntry>>();

  for (const entry of entries) {
    byId.set(entry.id, {
      key: String(entry.id),
      data: entry,
      children: [],
      leaf: true,
      expanded: true,
    });
  }

  const roots: TreeNode<WealthEntry>[] = [];

  for (const entry of entries) {
    const node = byId.get(entry.id);
    if (!node) {
      continue;
    }
    const parent = entry.parentId === null ? undefined : byId.get(entry.parentId);
    if (parent) {
      parent.children = [...(parent.children ?? []), node];
      parent.leaf = false;
    } else {
      roots.push(node);
    }
  }

  return roots;
}
