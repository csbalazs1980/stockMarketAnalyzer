import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { PageResponse } from '../../core/http/page-response';
import {
  AdminUser,
  AdminUserQuery,
  CreateUserRequest,
  RealmRole,
  ResetPasswordRequest,
} from './admin.models';

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
 * User administration, as far as the browser is concerned: six calls to this application.
 *
 * <p>No Keycloak URL appears here, exactly as none appears in `auth.api.ts`. The backend holds
 * the service-account credential that reaches the identity provider, so an administrator using
 * this screen needs no Keycloak account and no `realm-management` roles of their own.
 */
@Injectable({ providedIn: 'root' })
export class UserAdminApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/admin`;

  list(query: AdminUserQuery = {}): Observable<PageResponse<AdminUser>> {
    return this.http.get<PageResponse<AdminUser>>(`${this.base}/users`, {
      params: toParams({ ...query }),
    });
  }

  /** The realm's assignable roles. Fetched once per visit and cached by the page. */
  roles(): Observable<RealmRole[]> {
    return this.http.get<RealmRole[]>(`${this.base}/roles`);
  }

  create(body: CreateUserRequest): Observable<AdminUser> {
    return this.http.post<AdminUser>(`${this.base}/users`, body);
  }

  resetPassword(id: string, body: ResetPasswordRequest): Observable<void> {
    return this.http.put<void>(`${this.base}/users/${id}/password`, body);
  }

  /** The roles sent are the roles the user ends up with; the backend diffs it. */
  setRoles(id: string, roles: readonly string[]): Observable<AdminUser> {
    return this.http.put<AdminUser>(`${this.base}/users/${id}/roles`, { roles });
  }

  setEnabled(id: string, enabled: boolean): Observable<AdminUser> {
    return this.http.put<AdminUser>(`${this.base}/users/${id}/enabled`, { enabled });
  }
}
