import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { merge } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { problemMessage } from '../../core/http/problem-detail';
import { CsvImportDialog } from '../../shared/csv-import-dialog';
import { ExpenseCategoryApi } from './expense.api';
import { ExpenseCategory } from './expense.models';
import { PAGE_SIZE_OPTIONS } from '../../core/table/page-size';

/**
 * Which kinds the list is narrowed to. A string rather than `null` for "both": p-selectbutton
 * identifies the chosen option by its value, and a null value is indistinguishable from nothing
 * being selected - so the All button would never light up.
 */
type Scope = 'all' | 'global' | 'personal';

/**
 * CRUD over expense categories - the tags expense items carry.
 *
 * Two kinds live in one table: the signed-in user's own, and global ones shared by the
 * installation. An ordinary user sees both and may only edit their own, so for them this screen is
 * a plain personal tag list with some read-only rows in it. An administrator additionally gets the
 * "global" switch, on the form and on the import dialog.
 *
 * The global switch is *hidden* rather than disabled for anyone without the role, for the same
 * reason the Administration menu item is: the API refuses it regardless, so showing it would only
 * advertise a door that does not open.
 */
@Component({
  selector: 'app-expense-categories-page',
  imports: [
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    CsvImportDialog,
    DialogModule,
    InputTextModule,
    SelectButtonModule,
    TableModule,
    TagModule,
    ToggleSwitchModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <h1>{{ 'expenses.categories.title' | transloco }}</h1>
      <div class="row-actions">
        <p-button
          icon="pi pi-refresh"
          [text]="true"
          [ariaLabel]="'common.refresh' | transloco"
          (onClick)="reload()"
        />
        <p-button
          icon="pi pi-upload"
          [label]="'import.button' | transloco"
          severity="secondary"
          [outlined]="true"
          (onClick)="importOpen.set(true)"
        />
        <p-button
          icon="pi pi-plus"
          [label]="'expenses.categories.new' | transloco"
          (onClick)="openCreate()"
        />
      </div>
    </div>

    <p class="muted page-hint">{{ 'expenses.categories.hint' | transloco }}</p>

    <div class="filter-bar">
      <div class="field">
        <label for="c-search">{{ 'expenses.categories.search' | transloco }}</label>
        <input
          pInputText
          id="c-search"
          [ngModel]="search()"
          (ngModelChange)="search.set($event)"
        />
      </div>
      <div class="field">
        <label id="c-scope-label">{{ 'expenses.categories.scope' | transloco }}</label>
        <p-selectbutton
          [options]="scopeOptions()"
          [ngModel]="scope()"
          (ngModelChange)="onScopeChange($event)"
          optionLabel="label"
          optionValue="value"
          [allowEmpty]="false"
          ariaLabelledBy="c-scope-label"
        />
      </div>
    </div>

    <div class="table-scroll">
      <p-table
        [value]="visible()"
        [loading]="loading()"
        [paginator]="visible().length > pageSizeOptions[0]"
        [rows]="pageSizeOptions[0]"
        [rowsPerPageOptions]="pageSizeOptions"
        [showCurrentPageReport]="true"
        [currentPageReportTemplate]="'table.pageReport' | transloco"
        sortField="category"
        [sortOrder]="1"
        dataKey="id"
        styleClass="p-datatable-sm"
      >
        <ng-template #header>
          <tr>
            <th pSortableColumn="category" style="min-width: 16rem">
              {{ 'expenses.categories.fields.name' | transloco }} <p-sortIcon field="category" />
            </th>
            <th pSortableColumn="global" style="min-width: 8rem">
              {{ 'expenses.categories.fields.kind' | transloco }} <p-sortIcon field="global" />
            </th>
            <th style="width: 7rem"></th>
          </tr>
        </ng-template>

        <ng-template #body let-category>
          <tr>
            <td>{{ category.category }}</td>
            <td>
              <p-tag
                [value]="
                  (category.global ? 'expenses.categories.global' : 'expenses.categories.personal')
                    | transloco
                "
                [severity]="category.global ? 'info' : 'secondary'"
              />
            </td>
            <td>
              <div class="row-actions">
                <p-button
                  icon="pi pi-pencil"
                  [text]="true"
                  [rounded]="true"
                  [disabled]="!canEdit(category)"
                  [ariaLabel]="'common.edit' | transloco"
                  (onClick)="openEdit(category)"
                />
                <p-button
                  icon="pi pi-trash"
                  severity="danger"
                  [text]="true"
                  [rounded]="true"
                  [disabled]="!canEdit(category)"
                  [ariaLabel]="'common.delete' | transloco"
                  (onClick)="confirmDelete(category)"
                />
              </div>
            </td>
          </tr>
        </ng-template>

        <ng-template #emptymessage>
          <tr>
            <td colspan="3" class="muted">{{ 'expenses.categories.empty' | transloco }}</td>
          </tr>
        </ng-template>

        <ng-template #summary>
          <span class="muted table-count">
            {{ 'table.rowCount' | transloco: { count: visible().length } }}
          </span>
        </ng-template>
      </p-table>
    </div>

    <p-dialog
      [visible]="dialogOpen()"
      (visibleChange)="dialogOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(30rem, 95vw)' }"
      [header]="
        (editingId() === null
          ? 'expenses.categories.newTitle'
          : 'expenses.categories.editTitle') | transloco
      "
    >
      <div class="dialog-form one-column">
        <div class="field">
          <label for="f-name">{{ 'expenses.categories.fields.name' | transloco }}</label>
          <input
            pInputText
            id="f-name"
            [ngModel]="name()"
            (ngModelChange)="name.set($event)"
            autocomplete="off"
          />
        </div>

        @if (isAdmin()) {
          <div class="field switch-field">
            <p-toggleswitch
              inputId="f-global"
              [ngModel]="global()"
              (ngModelChange)="global.set($event)"
              [disabled]="editingId() !== null"
            />
            <label for="f-global">{{ 'expenses.categories.fields.global' | transloco }}</label>
          </div>
          <p class="muted no-margin">
            {{
              (editingId() === null
                ? 'expenses.categories.globalHint'
                : 'expenses.categories.kindFixedHint') | transloco
            }}
          </p>
        }
      </div>

      <ng-template #footer>
        <p-button
          [label]="'common.cancel' | transloco"
          severity="secondary"
          [text]="true"
          (onClick)="dialogOpen.set(false)"
        />
        <p-button
          [label]="'common.save' | transloco"
          icon="pi pi-check"
          [loading]="saving()"
          [disabled]="name().trim().length === 0"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>

    <app-csv-import-dialog
      [(visible)]="importOpen"
      [endpoint]="api"
      titleKey="import.expenseCategories.title"
      hintKey="import.expenseCategories.hint"
      sampleFileName="expense-categories-sample.csv"
      exportBaseName="expense-categories"
      [toggleField]="isAdmin() ? 'global' : null"
      [toggleLabelKey]="isAdmin() ? 'expenses.categories.importGlobal' : null"
      [toggleHintKey]="isAdmin() ? 'expenses.categories.globalHint' : null"
      (imported)="reload()"
    />
  `,
  styles: `
    .page-hint {
      margin-top: -0.5rem;
    }

    /* One field plus a switch: a two-column grid would leave a hole beside the name. */
    .dialog-form.one-column {
      grid-template-columns: 1fr;
    }

    .switch-field {
      flex-direction: row;
      align-items: center;
      gap: 0.6rem;
    }

    .no-margin {
      margin: 0;
    }
  `,
})
export class ExpenseCategoriesPage {
  /** Protected rather than private: the import dialog takes it as an input from the template. */
  protected readonly api = inject(ExpenseCategoryApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly transloco = inject(TranslocoService);

  protected readonly loading = signal(false);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  protected readonly saving = signal(false);
  protected readonly categories = signal<ExpenseCategory[]>([]);

  protected readonly search = signal('');
  protected readonly scope = signal<Scope>('all');

  protected readonly dialogOpen = signal(false);
  protected readonly importOpen = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly name = signal('');
  protected readonly global = signal(false);

  protected readonly isAdmin = this.auth.isAdmin;

  /** Re-render the imperatively translated bits whenever a language is switched or loaded. */
  private readonly i18nTick = toSignal(
    merge(this.transloco.langChanges$, this.transloco.events$),
    { initialValue: null },
  );

  protected readonly scopeOptions = computed(() => {
    this.i18nTick();
    return [
      { label: this.transloco.translate('expenses.categories.scopeAll'), value: 'all' as Scope },
      {
        label: this.transloco.translate('expenses.categories.personal'),
        value: 'personal' as Scope,
      },
      { label: this.transloco.translate('expenses.categories.global'), value: 'global' as Scope },
    ];
  });

  /**
   * Filtering happens in the browser rather than on the server. The whole tag list is fetched
   * anyway - a picker that stops at page one hides whatever sorts late in the alphabet - so
   * asking the server to narrow it again would be a round trip per keystroke for no new data.
   */
  protected readonly visible = computed(() => {
    const needle = this.search().trim().toLowerCase();
    const scope = this.scope();
    return this.categories().filter((category) => {
      if (scope === 'global' && !category.global) {
        return false;
      }
      if (scope === 'personal' && category.global) {
        return false;
      }
      return needle.length === 0 || category.category.toLowerCase().includes(needle);
    });
  });

  constructor() {
    this.reload();
  }

  /** A global row is read-only unless the caller is an administrator. */
  protected canEdit(category: ExpenseCategory): boolean {
    return !category.global || this.isAdmin();
  }

  protected onScopeChange(value: Scope): void {
    this.scope.set(value);
  }

  protected reload(): void {
    this.loading.set(true);
    this.api.listAll().subscribe({
      next: (categories) => {
        this.categories.set(categories);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected openCreate(): void {
    this.editingId.set(null);
    this.name.set('');
    this.global.set(false);
    this.dialogOpen.set(true);
  }

  protected openEdit(category: ExpenseCategory): void {
    this.editingId.set(category.id);
    this.name.set(category.category);
    // Shown, not editable: the backend refuses to move a row between the two kinds, because one
    // direction hands a personal label to everybody and the other strips a shared one from them.
    this.global.set(category.global);
    this.dialogOpen.set(true);
  }

  protected save(): void {
    const body = { category: this.name().trim(), global: this.global() };
    const id = this.editingId();
    const request$ = id === null ? this.api.create(body) : this.api.update(id, body);

    this.saving.set(true);
    request$.subscribe({
      next: () => {
        this.saving.set(false);
        this.dialogOpen.set(false);
        this.toast('expenses.categories.saved');
        this.reload();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.reportConflict(error);
      },
    });
  }

  /**
   * Plain delete first; the backend's 409 means the tag is still on some items, and that is turned
   * into a prompt which retries with force=true - the same shape as the wealth tree's cascade
   * delete. The global error interceptor deliberately passes 409 through untouched, so there is no
   * duplicate toast behind the prompt.
   */
  protected confirmDelete(category: ExpenseCategory): void {
    this.confirm.confirm({
      header: this.transloco.translate('expenses.categories.deleteTitle'),
      message: this.transloco.translate('expenses.categories.deleteMessage', {
        name: category.category,
      }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.transloco.translate('common.delete'),
      rejectLabel: this.transloco.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      accept: () =>
        this.api.remove(category.id).subscribe({
          next: () => {
            this.toast('expenses.categories.deleted');
            this.reload();
          },
          error: (error: unknown) => {
            if (error instanceof HttpErrorResponse && error.status === 409) {
              this.confirmForcedDelete(category, error);
            }
          },
        }),
    });
  }

  private confirmForcedDelete(category: ExpenseCategory, conflict: HttpErrorResponse): void {
    this.confirm.confirm({
      header: this.transloco.translate('expenses.categories.inUseTitle'),
      // The backend's own message says how many items carry it, which is the number that decides
      // whether somebody wants to go through with this.
      message: problemMessage(conflict, this.transloco.translate('errors.unknown')),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.transloco.translate('expenses.categories.untagAccept'),
      rejectLabel: this.transloco.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      accept: () =>
        this.api.remove(category.id, true).subscribe(() => {
          this.toast('expenses.categories.deleted');
          this.reload();
        }),
    });
  }

  /**
   * The global error interceptor lets 409 through untouched, because the wealth page turns its own
   * 409 into a prompt. Here a 409 on save is a name that already exists and there is nothing to
   * prompt about, so this page has to say so itself or the click would look like it did nothing.
   */
  private reportConflict(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 409) {
      this.messages.add({
        severity: 'error',
        summary: this.transloco.translate('expenses.categories.conflict'),
        detail: problemMessage(error, this.transloco.translate('errors.unknown')),
        life: 6000,
      });
    }
  }

  private toast(key: string): void {
    this.messages.add({
      severity: 'success',
      summary: this.transloco.translate(key),
      life: 2500,
    });
  }
}
