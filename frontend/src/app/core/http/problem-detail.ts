import { HttpErrorResponse } from '@angular/common/http';

/**
 * RFC 9457 problem details, which is what the backend returns for every error -
 * including the ones raised inside the security filter chain.
 */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  [key: string]: unknown;
}

export function toProblemDetail(error: HttpErrorResponse): ProblemDetail | null {
  const body: unknown = error.error;
  if (body && typeof body === 'object' && !(body instanceof ProgressEvent)) {
    return body as ProblemDetail;
  }
  return null;
}

/** Best available human-readable message for an HTTP failure. */
export function problemMessage(error: HttpErrorResponse, fallback: string): string {
  const problem = toProblemDetail(error);
  return problem?.detail || problem?.title || error.message || fallback;
}
