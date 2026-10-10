import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TooltipModule } from 'primeng/tooltip';
import { merge } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { LazyTableEvent, toSortParam } from '../../core/table/table-sort';
import { CsvImportDialog } from '../../shared/csv-import-dialog';
import { ExchangeRateApi } from './wealth.api';
import { ExchangeRate, ExchangeRateRequest } from './wealth.models';
import { PAGE_SIZE_OPTIONS } from '../../core/table/page-size';

interface RateFilters {
  sourceUnit: string;
  destinationUnit: string;
  from: string;
  to: string;
}

interface RateForm {
  sourceUnit: string;
  destinationUnit: string;
  rate: number | null;
  rateDate: string;
  global: boolean;
}

type Scope = 'all' | 'personal' | 'global';

const EMPTY_FILTERS: RateFilters = { sourceUnit: '', destinationUnit: '', from: '', to: '' };

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function emptyForm(): RateForm {
  return { sourceUnit: '', destinationUnit: 'HUF', rate: null, rateDate: today(), global: false };
}

@Component({
  selector: 'app-exchange-rates-page',
  imports: [
    DecimalPipe,
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    CsvImportDialog,
    DialogModule,
    InputNumberModule,
    InputTextModule,
    SelectButtonModule,
    TableModule,
    TagModule,
    ToggleSwitchModule,
    TooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <h1>{{ 'rates.title' | transloco }}</h1>
      <div class="row-actions">
        <p-button
          icon="pi pi-refresh"
          [text]="true"
          [ariaLabel]="'common.refresh' | transloco"
          (onClick)="reload()"
        />
        <!-- The feed writes global rates, which is an administrator's job; hidden, not disabled,
             for everyone else, like the other admin-only controls. -->
        @if (isAdmin()) {
          <p-button
            icon="pi pi-cloud-download"
            [label]="'rates.fetch' | transloco"
            severity="secondary"
            [outlined]="true"
            [loading]="fetching()"
            [pTooltip]="'rates.fetchHint' | transloco"
            tooltipPosition="bottom"
            (onClick)="fetchLatest()"
          />
        }
        <p-button
          icon="pi pi-upload"
          [label]="'import.button' | transloco"
          severity="secondary"
          [outlined]="true"
          (onClick)="importOpen.set(true)"
        />
        <p-button icon="pi pi-plus" [label]="'rates.new' | transloco" (onClick)="openCreate()" />
      </div>
    </div>

    <p class="muted rates-hint">{{ 'rates.hint' | transloco }}</p>

    <div class="filter-bar">
      <div class="field">
        <label for="r-source">{{ 'rates.fields.sourceUnit' | transloco }}</label>
        <input
          pInputText
          id="r-source"
          [ngModel]="draft().sourceUnit"
          (ngModelChange)="patchDraft({ sourceUnit: $event })"
          placeholder="USD"
        />
      </div>
      <div class="field">
        <label for="r-dest">{{ 'rates.fields.destinationUnit' | transloco }}</label>
        <input
          pInputText
          id="r-dest"
          [ngModel]="draft().destinationUnit"
          (ngModelChange)="patchDraft({ destinationUnit: $event })"
          placeholder="HUF"
        />
      </div>
      <div class="field">
        <label for="r-from">{{ 'wealth.filters.from' | transloco }}</label>
        <input
          id="r-from"
          class="app-input"
          type="date"
          [ngModel]="draft().from"
          (ngModelChange)="patchDraft({ from: $event })"
        />
      </div>
      <div class="field">
        <label id="r-scope-label">{{ 'rates.scope' | transloco }}</label>
        <p-selectbutton
          [options]="scopeOptions()"
          [ngModel]="scope()"
          (ngModelChange)="onScopeChange($event)"
          optionLabel="label"
          optionValue="value"
          [allowEmpty]="false"
          ariaLabelledBy="r-scope-label"
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
        [value]="rates()"
        [lazy]="true"
        (onLazyLoad)="onLazyLoad($event)"
        [loading]="loading()"
        [paginator]="true"
        [rows]="pageSize()"
        [totalRecords]="total()"
        [rowsPerPageOptions]="pageSizeOptions"
        [showCurrentPageReport]="true"
        [currentPageReportTemplate]="'table.pageReport' | transloco"
        sortField="rateDate"
        [sortOrder]="-1"
        dataKey="id"
        styleClass="p-datatable-sm"
      >
        <ng-template #header>
          <tr>
            <th pSortableColumn="sourceUnit" style="min-width: 7rem">
              {{ 'rates.fields.sourceUnit' | transloco }} <p-sortIcon field="sourceUnit" />
            </th>
            <th pSortableColumn="destinationUnit" style="min-width: 7rem">
              {{ 'rates.fields.destinationUnit' | transloco }}
              <p-sortIcon field="destinationUnit" />
            </th>
            <th pSortableColumn="rate" class="numeric" style="min-width: 10rem">
              {{ 'rates.fields.rate' | transloco }} <p-sortIcon field="rate" />
            </th>
            <th pSortableColumn="rateDate" style="min-width: 9rem">
              {{ 'rates.fields.rateDate' | transloco }} <p-sortIcon field="rateDate" />
            </th>
            <th pSortableColumn="global" style="min-width: 7rem">
              {{ 'rates.fields.kind' | transloco }} <p-sortIcon field="global" />
            </th>
            <th style="width: 7rem"></th>
          </tr>
        </ng-template>

        <ng-template #body let-rate>
          <tr>
            <td>{{ rate.sourceUnit }}</td>
            <td>{{ rate.destinationUnit }}</td>
            <td class="numeric">{{ rate.rate | number: '1.0-8' }}</td>
            <td>{{ rate.rateDate }}</td>
            <td>
              <p-tag
                [value]="(rate.global ? 'rates.global' : 'rates.personal') | transloco"
                [severity]="rate.global ? 'info' : 'secondary'"
              />
            </td>
            <td>
              <div class="row-actions">
                @if (canEdit(rate)) {
                  <p-button
                    icon="pi pi-pencil"
                    [text]="true"
                    [rounded]="true"
                    [ariaLabel]="'common.edit' | transloco"
                    (onClick)="openEdit(rate)"
                  />
                } @else {
                  <!-- A global row an ordinary user cannot change, but can override: their own
                       rate for the pair always wins, so this is the edit that is open to them. -->
                  <p-button
                    icon="pi pi-user-edit"
                    [text]="true"
                    [rounded]="true"
                    [pTooltip]="'rates.override' | transloco"
                    tooltipPosition="bottom"
                    [ariaLabel]="'rates.override' | transloco"
                    (onClick)="openOverride(rate)"
                  />
                }
                <p-button
                  icon="pi pi-trash"
                  severity="danger"
                  [text]="true"
                  [rounded]="true"
                  [disabled]="!canEdit(rate)"
                  [ariaLabel]="'common.delete' | transloco"
                  (onClick)="confirmDelete(rate)"
                />
              </div>
            </td>
          </tr>
        </ng-template>

        <ng-template #emptymessage>
          <tr>
            <td colspan="6" class="muted">{{ 'rates.empty' | transloco }}</td>
          </tr>
        </ng-template>
      </p-table>
    </div>

    <p-dialog
      [visible]="dialogOpen()"
      (visibleChange)="dialogOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(34rem, 95vw)' }"
      [header]="'rates.newTitle' | transloco"
    >
      <div class="dialog-form">
        <div class="field">
          <label for="f-source">{{ 'rates.fields.sourceUnit' | transloco }}</label>
          <input
            pInputText
            id="f-source"
            [ngModel]="form().sourceUnit"
            (ngModelChange)="patchForm({ sourceUnit: $event.toUpperCase() })"
            autocomplete="off"
          />
        </div>
        <div class="field">
          <label for="f-dest">{{ 'rates.fields.destinationUnit' | transloco }}</label>
          <input
            pInputText
            id="f-dest"
            [ngModel]="form().destinationUnit"
            (ngModelChange)="patchForm({ destinationUnit: $event.toUpperCase() })"
            autocomplete="off"
          />
        </div>
        <div class="field">
          <label for="f-rate">{{ 'rates.fields.rate' | transloco }}</label>
          <p-inputnumber
            inputId="f-rate"
            [ngModel]="form().rate"
            (ngModelChange)="patchForm({ rate: $event })"
            [minFractionDigits]="0"
            [maxFractionDigits]="8"
          />
        </div>
        <div class="field">
          <label for="f-ratedate">{{ 'rates.fields.rateDate' | transloco }}</label>
          <input
            id="f-ratedate"
            class="app-input"
            type="date"
            [ngModel]="form().rateDate"
            (ngModelChange)="patchForm({ rateDate: $event })"
          />
        </div>

        @if (isAdmin()) {
          <div class="field span-2 switch-field">
            <p-toggleswitch
              inputId="f-global"
              [ngModel]="form().global"
              (ngModelChange)="patchForm({ global: $event })"
              [disabled]="editing()"
            />
            <label for="f-global">{{ 'rates.fields.global' | transloco }}</label>
          </div>
          <p class="muted span-2 no-margin">
            {{ (editing() ? 'rates.kindFixedHint' : 'rates.globalHint') | transloco }}
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
          [disabled]="!formValid()"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>

    <app-csv-import-dialog
      [(visible)]="importOpen"
      [endpoint]="api"
      titleKey="import.rates.title"
      hintKey="import.rates.hint"
      sampleFileName="exchange-rates-sample.csv"
      exportBaseName="exchange-rates"
      [toggleField]="isAdmin() ? 'global' : null"
      [toggleLabelKey]="isAdmin() ? 'rates.importGlobal' : null"
      [toggleHintKey]="isAdmin() ? 'rates.globalHint' : null"
      (imported)="reload()"
    />
  `,
  styles: `
    .rates-hint {
      margin-top: -0.5rem;
    }

    .filter-actions {
      flex-direction: row;
      align-items: flex-end;
      gap: 0.5rem;
    }

    /* Label beside the switch rather than above it, as on the category form. */
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
  `,
})
export class ExchangeRatesPage {
  /** Protected rather than private: the import dialog takes it as an input from the template. */
  protected readonly api = inject(ExchangeRateApi);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly transloco = inject(TranslocoService);
  private readonly auth = inject(AuthService);

  /** Only an administrator writes global rates; everybody reads them. */
  protected readonly isAdmin = this.auth.isAdmin;

  /** Which kinds the table shows. Server-side, because the table is paged. */
  protected readonly scope = signal<Scope>('all');

  /** True while the dialog edits an existing row: the kind of a stored rate cannot change. */
  protected readonly editing = signal(false);

  /** Re-render the imperatively translated bits whenever a language is switched or loaded. */
  private readonly i18nTick = toSignal(
    merge(this.transloco.langChanges$, this.transloco.events$),
    { initialValue: null },
  );

  protected readonly scopeOptions = computed(() => {
    this.i18nTick();
    return [
      { label: this.transloco.translate('rates.scopeAll'), value: 'all' as Scope },
      { label: this.transloco.translate('rates.personal'), value: 'personal' as Scope },
      { label: this.transloco.translate('rates.global'), value: 'global' as Scope },
    ];
  });

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly rates = signal<ExchangeRate[]>([]);
  protected readonly total = signal(0);
  protected readonly pageSize = signal(PAGE_SIZE_OPTIONS[0]);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly pageIndex = signal(0);
  private readonly sort = signal('rateDate,desc');

  protected readonly draft = signal<RateFilters>({ ...EMPTY_FILTERS });
  private readonly applied = signal<RateFilters>({ ...EMPTY_FILTERS });

  protected readonly dialogOpen = signal(false);
  protected readonly importOpen = signal(false);
  protected readonly fetching = signal(false);
  protected readonly form = signal<RateForm>(emptyForm());

  protected readonly formValid = computed(() => {
    const value = this.form();
    return (
      value.sourceUnit.trim().length > 0 &&
      value.destinationUnit.trim().length > 0 &&
      value.rate !== null &&
      value.rate > 0
    );
  });

  protected patchDraft(patch: Partial<RateFilters>): void {
    this.draft.update((current) => ({ ...current, ...patch }));
  }

  protected patchForm(patch: Partial<RateForm>): void {
    this.form.update((current) => ({ ...current, ...patch }));
  }

  /**
   * p-table fires this on init and on every paginator interaction. Typed
   * structurally rather than importing TableLazyLoadEvent, which PrimeNG 21
   * publishes from 'primeng/types/table' rather than 'primeng/table'.
   */
  protected onLazyLoad(event: LazyTableEvent): void {
    const rows = event.rows ?? this.pageSize();
    this.pageSize.set(rows);
    this.pageIndex.set(rows > 0 ? Math.floor((event.first ?? 0) / rows) : 0);
    // "Kind" sorts on the owner column: global rows have none.
    this.sort.set(toSortParam(event, { global: 'ownerSub' }, 'rateDate,desc'));
    this.reload();
  }

  protected apply(): void {
    this.applied.set({ ...this.draft() });
    this.pageIndex.set(0);
    this.reload();
  }

  protected reset(): void {
    this.draft.set({ ...EMPTY_FILTERS });
    this.applied.set({ ...EMPTY_FILTERS });
    this.pageIndex.set(0);
    this.reload();
  }

  protected reload(): void {
    const filters = this.applied();
    this.loading.set(true);
    this.api
      .list({
        global: this.scope() === 'all' ? null : this.scope() === 'global',
        sourceUnit: filters.sourceUnit || null,
        destinationUnit: filters.destinationUnit || null,
        from: filters.from || null,
        to: filters.to || null,
        page: this.pageIndex(),
        size: this.pageSize(),
        sort: this.sort(),
      })
      .subscribe({
        next: (page) => {
          this.rates.set(page.content);
          this.total.set(page.totalElements);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  /**
   * Stores today's published rates for the configured pairs.
   *
   * The toast names the date the feed published them, not today's: on a Saturday those are two
   * different days, and the whole point of storing the publication date is undermined if the UI
   * then says something else. A failure is already a toast from the global error interceptor,
   * carrying the backend's own explanation of what the feed did.
   */
  protected fetchLatest(): void {
    this.fetching.set(true);
    this.api.refresh().subscribe({
      next: (result) => {
        this.fetching.set(false);
        if (result.stored === 0) {
          this.messages.add({
            severity: 'warn',
            summary: this.transloco.translate('rates.fetchedNone'),
            life: 5000,
          });
          return;
        }
        const dates = [...new Set(result.rates.map((rate) => rate.rateDate))].sort().join(', ');
        this.messages.add({
          severity: 'success',
          summary: this.transloco.translate('rates.fetched', {
            count: result.stored,
            date: dates,
            provider: result.provider,
          }),
          life: 4000,
        });
        this.reload();
      },
      error: () => this.fetching.set(false),
    });
  }

  protected onScopeChange(value: Scope): void {
    this.scope.set(value);
    this.pageIndex.set(0);
    this.reload();
  }

  /** A personal row is the caller's own; a global one is writable by an administrator only. */
  protected canEdit(rate: ExchangeRate): boolean {
    return !rate.global || this.isAdmin();
  }

  protected openCreate(): void {
    this.editing.set(false);
    this.form.set(emptyForm());
    this.dialogOpen.set(true);
  }

  /** POST upserts on (kind, pair, date), so editing is just a pre-filled create of the same kind. */
  protected openEdit(rate: ExchangeRate): void {
    this.editing.set(true);
    this.form.set({
      sourceUnit: rate.sourceUnit,
      destinationUnit: rate.destinationUnit,
      rate: rate.rate,
      rateDate: rate.rateDate,
      global: rate.global,
    });
    this.dialogOpen.set(true);
  }

  /**
   * A personal copy of a global rate, prefilled from it. Stored beside the global row, not over it,
   * and from then on it is the one this user is valued from.
   */
  protected openOverride(rate: ExchangeRate): void {
    this.editing.set(false);
    this.form.set({
      sourceUnit: rate.sourceUnit,
      destinationUnit: rate.destinationUnit,
      rate: rate.rate,
      rateDate: rate.rateDate,
      global: false,
    });
    this.dialogOpen.set(true);
  }

  protected save(): void {
    const value = this.form();
    if (value.rate === null) {
      return;
    }

    const body: ExchangeRateRequest = {
      sourceUnit: value.sourceUnit.trim().toUpperCase(),
      destinationUnit: value.destinationUnit.trim().toUpperCase(),
      rate: value.rate,
      rateDate: value.rateDate || null,
      // Never true for a non-administrator: the switch is not even shown to them.
      global: this.isAdmin() && value.global,
    };

    this.saving.set(true);
    this.api.upsert(body).subscribe({
      next: () => {
        this.saving.set(false);
        this.dialogOpen.set(false);
        this.toast('rates.saved');
        this.reload();
      },
      error: () => this.saving.set(false),
    });
  }

  protected confirmDelete(rate: ExchangeRate): void {
    this.confirm.confirm({
      header: this.transloco.translate('rates.deleteTitle'),
      message: this.transloco.translate('rates.deleteMessage', {
        source: rate.sourceUnit,
        destination: rate.destinationUnit,
        date: rate.rateDate,
      }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.transloco.translate('common.delete'),
      rejectLabel: this.transloco.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      accept: () =>
        this.api.remove(rate.id).subscribe(() => {
          this.toast('rates.deleted');
          this.reload();
        }),
    });
  }

  private toast(key: string): void {
    this.messages.add({
      severity: 'success',
      summary: this.transloco.translate(key),
      life: 2500,
    });
  }
}
