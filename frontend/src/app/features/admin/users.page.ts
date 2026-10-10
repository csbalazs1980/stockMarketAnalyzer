import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MultiSelectModule } from 'primeng/multiselect';
import { PasswordModule } from 'primeng/password';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TooltipModule } from 'primeng/tooltip';

import { AuthService } from '../../core/auth/auth.service';
import { problemMessage } from '../../core/http/problem-detail';
import { UserAdminApi } from './admin.api';
import { AdminUser, RealmRole } from './admin.models';
import { LazyTableEvent, sortInPlace } from '../../core/table/table-sort';
import { PAGE_SIZE_OPTIONS } from '../../core/table/page-size';

/** The realm's own floor is the one that matters; this is what stops an obvious mistake early. */
const MIN_PASSWORD_LENGTH = 8;

interface CreateForm {
  username: string;
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  temporaryPassword: boolean;
  roles: string[];
}

function emptyCreateForm(): CreateForm {
  return {
    username: '',
    firstName: '',
    lastName: '',
    email: '',
    password: '',
    // On by default: an administrator typing somebody else's password should be handing over a
    // stopgap, not a credential they go on knowing.
    temporaryPassword: true,
    roles: ['USER'],
  };
}

/**
 * User administration: create an account, replace its password, grant privileges, switch it off.
 *
 * <p>Everything here goes through `/api/v1/admin`, which requires the ADMIN realm role. The
 * `adminGuard` on the route is only an affordance - it decides whether the screen is worth
 * showing, not whether the calls are allowed.
 *
 * <p>Deleting a user is deliberately absent. Wealth entries and dividends are owned by the
 * Keycloak subject, so a deleted account leaves rows nothing can ever re-attach to; disabling
 * revokes every sign-in and keeps the history. The two guards below - you cannot disable
 * yourself, and you cannot drop your own ADMIN role - are enforced by the backend and merely
 * shown here, so the buttons that would fail are visibly unavailable rather than quietly broken.
 */
@Component({
  selector: 'app-users-page',
  imports: [
    DatePipe,
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    DialogModule,
    InputTextModule,
    MultiSelectModule,
    PasswordModule,
    TableModule,
    TagModule,
    ToggleSwitchModule,
    TooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <h1>{{ 'admin.users.title' | transloco }}</h1>
      <div class="row-actions">
        <p-button
          icon="pi pi-refresh"
          [text]="true"
          [ariaLabel]="'common.refresh' | transloco"
          (onClick)="reload()"
        />
        <p-button
          icon="pi pi-user-plus"
          [label]="'admin.users.new' | transloco"
          (onClick)="openCreate()"
        />
      </div>
    </div>

    <p class="muted users-hint">{{ 'admin.users.hint' | transloco }}</p>

    <div class="filter-bar">
      <div class="field">
        <label for="u-search">{{ 'admin.users.search' | transloco }}</label>
        <input
          pInputText
          id="u-search"
          [ngModel]="draftSearch()"
          (ngModelChange)="draftSearch.set($event)"
          (keyup.enter)="apply()"
          autocomplete="off"
        />
      </div>
      <div class="field filter-actions">
        <p-button [label]="'common.apply' | transloco" icon="pi pi-filter" (onClick)="apply()" />
        <p-button
          [label]="'common.reset' | transloco"
          severity="secondary"
          [text]="true"
          (onClick)="reset()"
        />
      </div>
    </div>

    <div class="table-scroll">
      <p-table
        [value]="users()"
        [lazy]="true"
        (onLazyLoad)="onLazyLoad($event)"
        [loading]="loading()"
        [paginator]="true"
        [rows]="pageSize()"
        [totalRecords]="total()"
        [rowsPerPageOptions]="pageSizeOptions"
        [showCurrentPageReport]="true"
        [currentPageReportTemplate]="'table.pageReport' | transloco"
        dataKey="id"
        styleClass="p-datatable-sm"
      >
        <ng-template #header>
          <tr>
            <th pSortableColumn="username" style="min-width: 10rem">
              {{ 'admin.users.fields.username' | transloco }} <p-sortIcon field="username" />
            </th>
            <th pSortableColumn="name" style="min-width: 10rem">
              {{ 'admin.users.fields.name' | transloco }} <p-sortIcon field="name" />
            </th>
            <th pSortableColumn="email" style="min-width: 12rem">
              {{ 'admin.users.fields.email' | transloco }} <p-sortIcon field="email" />
            </th>
            <th pSortableColumn="roles" style="min-width: 9rem">
              {{ 'admin.users.fields.roles' | transloco }} <p-sortIcon field="roles" />
            </th>
            <th pSortableColumn="enabled" style="min-width: 7rem">
              {{ 'admin.users.fields.status' | transloco }} <p-sortIcon field="enabled" />
            </th>
            <th pSortableColumn="createdAt" style="min-width: 8rem">
              {{ 'admin.users.fields.createdAt' | transloco }} <p-sortIcon field="createdAt" />
            </th>
            <th style="width: 10rem"></th>
          </tr>
        </ng-template>

        <ng-template #body let-user>
          <tr>
            <td>
              <span class="username">{{ user.username }}</span>
              @if (isSelf(user)) {
                <span class="muted you">{{ 'admin.users.you' | transloco }}</span>
              }
            </td>
            <td>{{ fullName(user) || '—' }}</td>
            <td>{{ user.email || '—' }}</td>
            <td>
              @if (user.roles.length) {
                <div class="role-tags">
                  @for (role of user.roles; track role) {
                    <p-tag [value]="role" [severity]="roleSeverity(role)" />
                  }
                </div>
              } @else {
                <span class="muted">{{ 'admin.users.noRoles' | transloco }}</span>
              }
            </td>
            <td>
              <p-tag
                [value]="
                  (user.enabled ? 'admin.users.enabled' : 'admin.users.disabled') | transloco
                "
                [severity]="user.enabled ? 'success' : 'secondary'"
              />
            </td>
            <td>{{ user.createdAt ? (user.createdAt | date: 'yyyy-MM-dd') : '—' }}</td>
            <td>
              <div class="row-actions">
                <p-button
                  icon="pi pi-key"
                  [text]="true"
                  [rounded]="true"
                  [pTooltip]="'admin.users.resetPassword' | transloco"
                  tooltipPosition="bottom"
                  [ariaLabel]="'admin.users.resetPassword' | transloco"
                  (onClick)="openReset(user)"
                />
                <p-button
                  icon="pi pi-shield"
                  [text]="true"
                  [rounded]="true"
                  [pTooltip]="'admin.users.roles' | transloco"
                  tooltipPosition="bottom"
                  [ariaLabel]="'admin.users.roles' | transloco"
                  (onClick)="openRoles(user)"
                />
                <p-button
                  [icon]="user.enabled ? 'pi pi-ban' : 'pi pi-check-circle'"
                  [severity]="user.enabled ? 'danger' : 'success'"
                  [text]="true"
                  [rounded]="true"
                  [disabled]="isSelf(user)"
                  [pTooltip]="
                    isSelf(user)
                      ? ('admin.users.notYourself' | transloco)
                      : ((user.enabled ? 'admin.users.disable' : 'admin.users.enable') | transloco)
                  "
                  tooltipPosition="bottom"
                  [ariaLabel]="
                    (user.enabled ? 'admin.users.disable' : 'admin.users.enable') | transloco
                  "
                  (onClick)="confirmToggleEnabled(user)"
                />
              </div>
            </td>
          </tr>
        </ng-template>

        <ng-template #emptymessage>
          <tr>
            <td colspan="7" class="muted">{{ 'admin.users.empty' | transloco }}</td>
          </tr>
        </ng-template>
      </p-table>
    </div>

    <!-- Create -->
    <p-dialog
      [visible]="createOpen()"
      (visibleChange)="createOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(40rem, 95vw)' }"
      [header]="'admin.users.newTitle' | transloco"
    >
      <div class="dialog-form">
        <div class="field">
          <label for="c-username">{{ 'admin.users.fields.username' | transloco }}</label>
          <input
            pInputText
            id="c-username"
            [ngModel]="createForm().username"
            (ngModelChange)="patchCreate({ username: $event })"
            autocomplete="off"
            autocapitalize="none"
            spellcheck="false"
          />
        </div>
        <div class="field">
          <label for="c-email">{{ 'admin.users.fields.email' | transloco }}</label>
          <input
            pInputText
            id="c-email"
            type="email"
            [ngModel]="createForm().email"
            (ngModelChange)="patchCreate({ email: $event })"
            autocomplete="off"
          />
        </div>
        <div class="field">
          <label for="c-first">{{ 'admin.users.fields.firstName' | transloco }}</label>
          <input
            pInputText
            id="c-first"
            [ngModel]="createForm().firstName"
            (ngModelChange)="patchCreate({ firstName: $event })"
            autocomplete="off"
          />
        </div>
        <div class="field">
          <label for="c-last">{{ 'admin.users.fields.lastName' | transloco }}</label>
          <input
            pInputText
            id="c-last"
            [ngModel]="createForm().lastName"
            (ngModelChange)="patchCreate({ lastName: $event })"
            autocomplete="off"
          />
        </div>
        <div class="field">
          <label for="c-password">{{ 'admin.users.fields.password' | transloco }}</label>
          <p-password
            inputId="c-password"
            [ngModel]="createForm().password"
            (ngModelChange)="patchCreate({ password: $event })"
            autocomplete="new-password"
            [feedback]="false"
            [toggleMask]="true"
            [fluid]="true"
          />
          <small class="muted">{{
            'admin.users.passwordHint' | transloco: { min: minPasswordLength }
          }}</small>
        </div>
        <div class="field">
          <label for="c-roles">{{ 'admin.users.fields.roles' | transloco }}</label>
          <p-multiselect
            inputId="c-roles"
            [options]="roleOptions()"
            [ngModel]="createForm().roles"
            (ngModelChange)="patchCreate({ roles: $event })"
            optionLabel="name"
            optionValue="name"
            display="chip"
            [showToggleAll]="false"
            [filter]="false"
            [fluid]="true"
            [placeholder]="'admin.users.noRoles' | transloco"
          />
        </div>
        <div class="field span-2 switch-field">
          <p-toggleswitch
            inputId="c-temp"
            [ngModel]="createForm().temporaryPassword"
            (ngModelChange)="patchCreate({ temporaryPassword: $event })"
          />
          <label for="c-temp">{{ 'admin.users.fields.temporaryPassword' | transloco }}</label>
        </div>
        <p class="muted span-2 no-margin">{{ 'admin.users.temporaryHint' | transloco }}</p>
      </div>

      <ng-template #footer>
        <p-button
          [label]="'common.cancel' | transloco"
          severity="secondary"
          [text]="true"
          (onClick)="createOpen.set(false)"
        />
        <p-button
          [label]="'common.save' | transloco"
          icon="pi pi-check"
          [loading]="saving()"
          [disabled]="!createValid()"
          (onClick)="saveCreate()"
        />
      </ng-template>
    </p-dialog>

    <!-- Reset password -->
    <p-dialog
      [visible]="resetOpen()"
      (visibleChange)="resetOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(30rem, 95vw)' }"
      [header]="'admin.users.resetTitle' | transloco: { username: selectedName() }"
    >
      <div class="single-column">
        <div class="field">
          <label for="r-password">{{ 'admin.users.fields.password' | transloco }}</label>
          <p-password
            inputId="r-password"
            [ngModel]="resetPassword()"
            (ngModelChange)="resetPassword.set($event)"
            autocomplete="new-password"
            [feedback]="false"
            [toggleMask]="true"
            [fluid]="true"
          />
          <small class="muted">{{
            'admin.users.passwordHint' | transloco: { min: minPasswordLength }
          }}</small>
        </div>
        <div class="field switch-field">
          <p-toggleswitch
            inputId="r-temp"
            [ngModel]="resetTemporary()"
            (ngModelChange)="resetTemporary.set($event)"
          />
          <label for="r-temp">{{ 'admin.users.fields.temporaryPassword' | transloco }}</label>
        </div>
        <p class="muted no-margin">{{ 'admin.users.resetHint' | transloco }}</p>
      </div>

      <ng-template #footer>
        <p-button
          [label]="'common.cancel' | transloco"
          severity="secondary"
          [text]="true"
          (onClick)="resetOpen.set(false)"
        />
        <p-button
          [label]="'admin.users.resetPassword' | transloco"
          icon="pi pi-key"
          [loading]="saving()"
          [disabled]="resetPassword().length < minPasswordLength"
          (onClick)="saveReset()"
        />
      </ng-template>
    </p-dialog>

    <!-- Roles -->
    <p-dialog
      [visible]="rolesOpen()"
      (visibleChange)="rolesOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(30rem, 95vw)' }"
      [header]="'admin.users.rolesTitle' | transloco: { username: selectedName() }"
    >
      <div class="single-column">
        <div class="field">
          <label for="e-roles">{{ 'admin.users.fields.roles' | transloco }}</label>
          <p-multiselect
            inputId="e-roles"
            [options]="roleOptions()"
            [ngModel]="editRoles()"
            (ngModelChange)="editRoles.set($event)"
            optionLabel="name"
            optionValue="name"
            display="chip"
            [showToggleAll]="false"
            [filter]="false"
            [fluid]="true"
            [placeholder]="'admin.users.noRoles' | transloco"
          />
        </div>
        @if (roleDescriptions().length) {
          <ul class="role-legend muted">
            @for (role of roleDescriptions(); track role.name) {
              <li><strong>{{ role.name }}</strong> — {{ role.description }}</li>
            }
          </ul>
        }
        @if (lockedOutOfAdmin()) {
          <p class="warn no-margin">{{ 'admin.users.notYourAdmin' | transloco }}</p>
        }
      </div>

      <ng-template #footer>
        <p-button
          [label]="'common.cancel' | transloco"
          severity="secondary"
          [text]="true"
          (onClick)="rolesOpen.set(false)"
        />
        <p-button
          [label]="'common.save' | transloco"
          icon="pi pi-check"
          [loading]="saving()"
          [disabled]="lockedOutOfAdmin()"
          (onClick)="saveRoles()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .users-hint {
      margin-top: -0.5rem;
    }

    .filter-actions {
      flex-direction: row;
      align-items: flex-end;
      gap: 0.5rem;
    }

    .username {
      font-weight: 600;
    }

    .you {
      margin-left: 0.4rem;
      font-size: 0.75rem;
    }

    .role-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem;
    }

    .single-column {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    /* Label beside the switch rather than above it: a switch labelled from above reads as a
       heading for the rest of the form. */
    .switch-field {
      flex-direction: row;
      align-items: center;
      gap: 0.6rem;
    }

    .switch-field > label {
      opacity: 1;
      font-weight: 500;
    }

    .no-margin {
      margin: 0;
      font-size: 0.85rem;
    }

    .role-legend {
      margin: 0;
      padding-left: 1.1rem;
      font-size: 0.8rem;
      line-height: 1.5;
    }

    .warn {
      margin: 0;
      font-size: 0.85rem;
      color: var(--p-red-500, #ef4444);
    }
  `,
})
export class UsersPage {
  private readonly api = inject(UserAdminApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly transloco = inject(TranslocoService);

  protected readonly minPasswordLength = MIN_PASSWORD_LENGTH;

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly users = signal<AdminUser[]>([]);
  protected readonly total = signal(0);
  protected readonly pageSize = signal(PAGE_SIZE_OPTIONS[0]);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly pageIndex = signal(0);
  private readonly sortField = signal<string | null>(null);
  private readonly sortOrder = signal(1);

  protected readonly draftSearch = signal('');
  private readonly appliedSearch = signal('');

  /** The realm's roles, fetched once: they change when somebody edits the realm, not per page. */
  protected readonly roleOptions = signal<RealmRole[]>([]);

  protected readonly createOpen = signal(false);
  protected readonly resetOpen = signal(false);
  protected readonly rolesOpen = signal(false);

  protected readonly createForm = signal<CreateForm>(emptyCreateForm());
  protected readonly resetPassword = signal('');
  protected readonly resetTemporary = signal(true);
  protected readonly editRoles = signal<string[]>([]);

  private readonly selected = signal<AdminUser | null>(null);

  protected readonly selectedName = computed(() => this.selected()?.username ?? '');

  protected readonly roleDescriptions = computed(() =>
    this.roleOptions().filter(
      (role): role is RealmRole & { description: string } => !!role.description,
    ),
  );

  protected readonly createValid = computed(() => {
    const form = this.createForm();
    return (
      form.username.trim().length > 0 && form.password.length >= MIN_PASSWORD_LENGTH
    );
  });

  /**
   * The backend refuses to let an administrator drop their own ADMIN role, because the screen
   * that could undo it is the one they would lose. Shown here so the Save button is visibly
   * unavailable rather than rejected after the click.
   */
  protected readonly lockedOutOfAdmin = computed(() => {
    const user = this.selected();
    return !!user && this.isSelf(user) && !this.editRoles().includes('ADMIN');
  });

  constructor() {
    this.api.roles().subscribe((roles) => this.roleOptions.set(roles));
  }

  protected patchCreate(patch: Partial<CreateForm>): void {
    this.createForm.update((current) => ({ ...current, ...patch }));
  }

  protected isSelf(user: AdminUser): boolean {
    return user.username === this.auth.session()?.username;
  }

  protected fullName(user: AdminUser): string {
    return [user.firstName, user.lastName].filter(Boolean).join(' ');
  }

  protected roleSeverity(role: string): 'danger' | 'info' {
    return role === 'ADMIN' ? 'danger' : 'info';
  }

  /**
   * Keycloak's user listing has no sort parameter, so a clicked column orders the page that is on
   * screen rather than the whole directory. In practice that is the whole directory: an
   * installation like this has a page of users, not thousands.
   */
  protected onLazyLoad(event: LazyTableEvent): void {
    const rows = event.rows ?? this.pageSize();
    this.pageSize.set(rows);
    this.pageIndex.set(rows > 0 ? Math.floor((event.first ?? 0) / rows) : 0);
    this.sortField.set(typeof event.sortField === 'string' ? event.sortField : null);
    this.sortOrder.set(event.sortOrder ?? 1);
    this.reload();
  }

  private sorted(users: AdminUser[]): AdminUser[] {
    const field = this.sortField();
    if (!field) {
      return users;
    }
    const keys: Record<string, (user: AdminUser) => unknown> = {
      username: (user) => user.username,
      name: (user) => this.fullName(user) || null,
      email: (user) => user.email || null,
      roles: (user) => user.roles.join(', ') || null,
      enabled: (user) => user.enabled,
      createdAt: (user) => user.createdAt ?? null,
    };
    const copy = [...users];
    sortInPlace(copy, keys[field] ?? ((user) => user.username), this.sortOrder());
    return copy;
  }

  protected apply(): void {
    this.appliedSearch.set(this.draftSearch().trim());
    this.pageIndex.set(0);
    this.reload();
  }

  protected reset(): void {
    this.draftSearch.set('');
    this.appliedSearch.set('');
    this.pageIndex.set(0);
    this.reload();
  }

  protected reload(): void {
    this.loading.set(true);
    this.api
      .list({
        search: this.appliedSearch() || null,
        page: this.pageIndex(),
        size: this.pageSize(),
      })
      .subscribe({
        next: (page) => {
          this.users.set(this.sorted(page.content));
          this.total.set(page.totalElements);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  protected openCreate(): void {
    this.createForm.set(emptyCreateForm());
    this.createOpen.set(true);
  }

  protected saveCreate(): void {
    const form = this.createForm();
    this.saving.set(true);
    this.api
      .create({
        username: form.username.trim(),
        email: form.email.trim() || null,
        firstName: form.firstName.trim() || null,
        lastName: form.lastName.trim() || null,
        password: form.password,
        temporaryPassword: form.temporaryPassword,
        roles: form.roles,
      })
      .subscribe({
        next: (user) => {
          this.saving.set(false);
          this.createOpen.set(false);
          this.toast('admin.users.created', { username: user.username });
          this.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.reportConflict(error);
        },
      });
  }

  protected openReset(user: AdminUser): void {
    this.selected.set(user);
    this.resetPassword.set('');
    this.resetTemporary.set(true);
    this.resetOpen.set(true);
  }

  protected saveReset(): void {
    const user = this.selected();
    if (!user) {
      return;
    }
    this.saving.set(true);
    this.api
      .resetPassword(user.id, {
        password: this.resetPassword(),
        temporary: this.resetTemporary(),
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.resetOpen.set(false);
          // Cleared rather than left in the signal: a password that stays in memory after the
          // dialog closes is one more place it can be read from.
          this.resetPassword.set('');
          this.toast('admin.users.passwordReset', { username: user.username });
        },
        error: () => this.saving.set(false),
      });
  }

  protected openRoles(user: AdminUser): void {
    this.selected.set(user);
    this.editRoles.set([...user.roles]);
    this.rolesOpen.set(true);
  }

  protected saveRoles(): void {
    const user = this.selected();
    if (!user) {
      return;
    }
    this.saving.set(true);
    this.api.setRoles(user.id, this.editRoles()).subscribe({
      next: () => {
        this.saving.set(false);
        this.rolesOpen.set(false);
        this.toast('admin.users.rolesSaved', { username: user.username });
        this.reload();
      },
      error: () => this.saving.set(false),
    });
  }

  /**
   * Disabling is consequential and irreversible from the user's side, so it is confirmed.
   * Re-enabling is not: it gives access back, and asking twice for that is noise.
   */
  protected confirmToggleEnabled(user: AdminUser): void {
    if (user.enabled) {
      this.confirm.confirm({
        header: this.transloco.translate('admin.users.disableTitle'),
        message: this.transloco.translate('admin.users.disableMessage', {
          username: user.username,
        }),
        icon: 'pi pi-exclamation-triangle',
        acceptLabel: this.transloco.translate('admin.users.disable'),
        rejectLabel: this.transloco.translate('common.cancel'),
        acceptButtonStyleClass: 'p-button-danger',
        accept: () => this.applyEnabled(user, false),
      });
      return;
    }
    this.applyEnabled(user, true);
  }

  private applyEnabled(user: AdminUser, enabled: boolean): void {
    this.api.setEnabled(user.id, enabled).subscribe(() => {
      this.toast(enabled ? 'admin.users.enabledToast' : 'admin.users.disabledToast', {
        username: user.username,
      });
      this.reload();
    });
  }

  /**
   * The global error interceptor lets 409 through untouched, because the wealth page turns its
   * own 409 into a prompt. Here a 409 is a taken username and there is nothing to prompt about,
   * so this page has to say so itself or the click would look like it did nothing.
   */
  private reportConflict(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 409) {
      this.messages.add({
        severity: 'error',
        summary: this.transloco.translate('admin.users.conflict'),
        detail: problemMessage(error, this.transloco.translate('errors.unknown')),
        life: 6000,
      });
    }
  }

  private toast(key: string, params: Record<string, unknown>): void {
    this.messages.add({
      severity: 'success',
      summary: this.transloco.translate(key, params),
      life: 3000,
    });
  }
}
