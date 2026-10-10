import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, mergeMap, Observable, of, range, switchMap, toArray } from 'rxjs';

import { environment } from '../../../environments/environment';
import { CsvImportEndpoint, CsvImportResult } from '../../core/csv/csv-import';
import { PageResponse } from '../../core/http/page-response';
import {
  ExpenseCategory,
  ExpenseCategoryQuery,
  ExpenseCategoryRequest,
  ExpenseItem,
  ExpenseItemQuery,
  ExpenseItemRequest,
} from './expense.models';

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

/**
 * Reads every page of a paged endpoint. The first page also reports how many there are, so the
 * rest go out together rather than one round trip at a time.
 */
function allPages<T>(
  first: PageResponse<T>,
  fetch: (page: number) => Observable<PageResponse<T>>,
): Observable<T[]> {
  if (first.totalPages <= 1) {
    return of(first.content);
  }
  return range(1, first.totalPages - 1).pipe(
    mergeMap((index) => fetch(index), MAX_IN_FLIGHT),
    toArray(),
    map((pages) => [
      ...first.content,
      ...pages.sort((a, b) => a.page - b.page).flatMap((page) => page.content),
    ]),
  );
}

@Injectable({ providedIn: 'root' })
export class ExpenseItemApi implements CsvImportEndpoint {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/expenses/items`;

  list(query: ExpenseItemQuery = {}): Observable<PageResponse<ExpenseItem>> {
    return this.http.get<PageResponse<ExpenseItem>>(this.base, { params: toParams({ ...query }) });
  }

  /**
   * Every matching row, not a page of them.
   *
   * Unlike the dividends table, this page does not use server-side paging at all: both the totals
   * and the grouped-by-category view are computed in the browser, and either of them built from a
   * page would be a figure that changed as you paged - which is worse than no figure. A personal
   * list of recurring bills is tens of rows, so the whole set is the right unit of work.
   */
  listAll(query: ExpenseItemQuery = {}, pageSize = PAGE_SIZE): Observable<ExpenseItem[]> {
    return this.list({ ...query, page: 0, size: pageSize }).pipe(
      switchMap((first) =>
        allPages(first, (page) => this.list({ ...query, page, size: pageSize })),
      ),
    );
  }

  create(body: ExpenseItemRequest): Observable<ExpenseItem> {
    return this.http.post<ExpenseItem>(this.base, body);
  }

  update(id: number, body: ExpenseItemRequest): Observable<ExpenseItem> {
    return this.http.put<ExpenseItem>(`${this.base}/${id}`, body);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  /**
   * Uploads a whole file of recurring costs. No Content-Type is set on purpose: the browser has to
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

@Injectable({ providedIn: 'root' })
export class ExpenseCategoryApi implements CsvImportEndpoint {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/expenses/categories`;

  list(query: ExpenseCategoryQuery = {}): Observable<PageResponse<ExpenseCategory>> {
    return this.http.get<PageResponse<ExpenseCategory>>(this.base, {
      params: toParams({ ...query }),
    });
  }

  /** The whole tag set - a picker that stops at page one hides whatever sorts late. */
  listAll(query: ExpenseCategoryQuery = {}, pageSize = PAGE_SIZE): Observable<ExpenseCategory[]> {
    return this.list({ ...query, page: 0, size: pageSize }).pipe(
      switchMap((first) =>
        allPages(first, (page) => this.list({ ...query, page, size: pageSize })),
      ),
    );
  }

  create(body: ExpenseCategoryRequest): Observable<ExpenseCategory> {
    return this.http.post<ExpenseCategory>(this.base, body);
  }

  /** Renaming only: the backend refuses to move a row between personal and global. */
  update(id: number, body: ExpenseCategoryRequest): Observable<ExpenseCategory> {
    return this.http.put<ExpenseCategory>(`${this.base}/${id}`, body);
  }

  /**
   * A tag still carried by expense items is a 409 unless force is set. The page catches that and
   * turns it into a prompt, exactly as the wealth tree does with its cascade delete.
   */
  remove(id: number, force = false): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`, { params: toParams({ force }) });
  }

  /**
   * Uploads a list of tag names. `extras` carries the dialog's global switch: whether a file lands
   * as global is asked once, for the whole file, rather than as a column every row has to answer -
   * see ExpenseCategoryService for why that is the safe reading.
   */
  importCsv(file: File, extras?: Record<string, string>): Observable<CsvImportResult> {
    const form = new FormData();
    form.append('file', file);
    for (const [key, value] of Object.entries(extras ?? {})) {
      form.append(key, value);
    }
    return this.http.post<CsvImportResult>(`${this.base}/import`, form);
  }

  downloadSample(): Observable<Blob> {
    return this.http.get(`${this.base}/import/sample`, { responseType: 'blob' });
  }

  /** The caller's own tags. Global ones are left out: they are not the caller's to export. */
  exportCsv(): Observable<Blob> {
    return this.http.get(`${this.base}/export`, { responseType: 'blob' });
  }
}
