/**
 * Mirrors com.stockmarketanalyzer.backend.common.PageResponse - the one paged envelope every
 * list endpoint returns. It lives in core/ rather than in a feature because more than one
 * feature consumes it and they must not drift apart.
 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
