import { Observable } from 'rxjs';

/**
 * What one CSV upload did, mirroring com.stockmarketanalyzer.backend.common.csv.CsvImportResult.
 *
 * processedRows + failedRows + duplicateRows === totalRows, so "132/136 rows processed" can
 * always be followed by an account of the other four.
 */
export interface CsvImportResult {
  /** Data rows in the file - the header and blank lines are not counted. */
  totalRows: number;
  processedRows: number;
  /** Rows that could not be read. Which rows they were is deliberately not reported yet. */
  failedRows: number;
  /** Rows that were already stored. Not a failure: it is what an overlapping upload looks like. */
  duplicateRows: number;
}

/**
 * The three calls the import dialog needs. Both API services implement it, so the dialog is written
 * once against this rather than once per feature - and adding a third importable entity is a
 * matter of implementing two methods, not copying a component.
 *
 * `extras` carries the dialog's optional switch as an extra multipart field - the expense
 * categories import uses it for "store these as global categories", which is one answer for the
 * whole file rather than a column every row has to fill in. It is optional, so an endpoint that
 * has nothing to ask still satisfies this interface with a one-parameter method.
 */
export interface CsvImportEndpoint {
  importCsv(file: File, extras?: Record<string, string>): Observable<CsvImportResult>;
  downloadSample(): Observable<Blob>;
  /**
   * The caller's own rows, in the columns the sample shows. The server decides what "own" means;
   * the dialog only has to save what comes back.
   */
  exportCsv(): Observable<Blob>;
}

/**
 * Hands a downloaded blob to the browser as a file.
 *
 * The sample is fetched through HttpClient rather than linked to directly, because the endpoint
 * sits behind the same bearer token as everything else and a plain <a href> would arrive without
 * one. That means the bytes are in memory, so the download is triggered from an object URL - and
 * the URL is revoked afterwards, or the blob stays alive for the life of the document.
 */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

/**
 * Today as YYYY-MM-DD, for stamping an exported file.
 *
 * Built from the local date rather than toISOString(), which is UTC: an export taken at eleven at
 * night in Budapest would otherwise be filed under tomorrow.
 */
export function isoToday(): string {
  const now = new Date();
  const pad = (value: number): string => String(value).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** True when the name ends in .csv, whatever case it was typed in. */
export function looksLikeCsv(file: File): boolean {
  return file.name.toLowerCase().endsWith('.csv');
}
