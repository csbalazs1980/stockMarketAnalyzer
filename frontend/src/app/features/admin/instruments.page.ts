import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TooltipModule } from 'primeng/tooltip';
import { merge } from 'rxjs';

import { problemMessage } from '../../core/http/problem-detail';
import { CsvImportDialog } from '../../shared/csv-import-dialog';
import { InstrumentApi } from '../instruments/instrument.api';
import {
  ASSET_TYPES,
  AssetType,
  Instrument,
  InstrumentRequest,
} from '../instruments/instrument.models';
import { InstrumentStore } from '../instruments/instrument.store';
import { LazyTableEvent, toSortParam } from '../../core/table/table-sort';
import { PAGE_SIZE_OPTIONS } from '../../core/table/page-size';

/** The backend's own @Pattern on InstrumentRequest.symbol, checked here to save a round trip. */
const SYMBOL_PATTERN = /^[A-Za-z0-9.\-^:]+$/;
const CURRENCY_PATTERN = /^[A-Za-z]{3}$/;

type StatusFilter = 'all' | 'active' | 'inactive';

interface Filters {
  search: string;
  assetType: AssetType | null;
  exchange: string;
  status: StatusFilter;
}

function emptyFilters(): Filters {
  return { search: '', assetType: null, exchange: '', status: 'all' };
}

interface InstrumentForm {
  symbol: string;
  name: string;
  exchange: string;
  currency: string;
  assetType: AssetType;
  sector: string;
  active: boolean;
}

function emptyForm(): InstrumentForm {
  return {
    symbol: '',
    name: '',
    exchange: '',
    currency: 'USD',
    assetType: 'STOCK',
    sector: '',
    active: true,
  };
}

function toForm(instrument: Instrument): InstrumentForm {
  return {
    symbol: instrument.symbol,
    name: instrument.name,
    exchange: instrument.exchange ?? '',
    currency: instrument.currency,
    assetType: instrument.assetType,
    sector: instrument.sector ?? '',
    active: instrument.active,
  };
}

function toRequest(form: InstrumentForm): InstrumentRequest {
  return {
    symbol: form.symbol.trim().toUpperCase(),
    name: form.name.trim(),
    exchange: form.exchange.trim() || null,
    currency: form.currency.trim().toUpperCase(),
    assetType: form.assetType,
    sector: form.sector.trim() || null,
    active: form.active,
  };
}

/**
 * Instrument administration: the reference data every picker in the app reads from.
 *
 * <p>The writes go to `/api/v1/instruments`, whose POST/PUT/DELETE already require ADMIN; the
 * `adminGuard` on the route only decides whether the screen is worth showing.
 *
 * <p>Every successful write invalidates {@link InstrumentStore}. Without that the dividend pickers
 * would go on offering the old universe for up to half an hour after an edit made in the same tab.
 *
 * <p><strong>Delete is refused for an instrument somebody has recorded dividends against</strong>
 * (the backend answers 409 with the reason). That is turned into an offer to deactivate instead,
 * which is what the administrator almost always meant: it leaves the pickers and keeps history.
 */
@Component({
  selector: 'app-instruments-page',
  imports: [
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    CsvImportDialog,
    DialogModule,
    InputTextModule,
    SelectModule,
    TableModule,
    TagModule,
    ToggleSwitchModule,
    TooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <h1>{{ 'admin.instruments.title' | transloco }}</h1>
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
          [label]="'admin.instruments.new' | transloco"
          (onClick)="openCreate()"
        />
      </div>
    </div>

    <p class="muted instruments-hint">{{ 'admin.instruments.hint' | transloco }}</p>

    <div class="filter-bar">
      <div class="field">
        <label for="i-search">{{ 'admin.instruments.search' | transloco }}</label>
        <input
          pInputText
          id="i-search"
          [ngModel]="draft().search"
          (ngModelChange)="patchDraft({ search: $event })"
          (keyup.enter)="apply()"
          autocomplete="off"
        />
      </div>
      <div class="field">
        <label for="i-type">{{ 'admin.instruments.fields.assetType' | transloco }}</label>
        <p-select
          inputId="i-type"
          [options]="assetTypeOptions"
          [ngModel]="draft().assetType"
          (ngModelChange)="patchDraft({ assetType: $event })"
          [showClear]="true"
          appendTo="body"
          [placeholder]="'admin.instruments.anyType' | transloco"
        />
      </div>
      <div class="field">
        <label for="i-exchange">{{ 'admin.instruments.fields.exchange' | transloco }}</label>
        <input
          pInputText
          id="i-exchange"
          [ngModel]="draft().exchange"
          (ngModelChange)="patchDraft({ exchange: $event })"
          (keyup.enter)="apply()"
          placeholder="NYSE"
          autocomplete="off"
        />
      </div>
      <div class="field">
        <label for="i-status">{{ 'admin.instruments.fields.status' | transloco }}</label>
        <p-select
          inputId="i-status"
          [options]="statusOptions()"
          [ngModel]="draft().status"
          (ngModelChange)="patchDraft({ status: $event })"
          optionLabel="label"
          optionValue="value"
          appendTo="body"
        />
      </div>
      <div class="field filter-actions">
        <p-button [label]="'common.apply' | transloco" icon="pi pi-filter" (onClick)="apply()" />
        <p-button
          [label]="'common.reset' | transloco"
          icon="pi pi-times"
          severity="secondary"
          [text]="true"
          (onClick)="reset()"
        />
      </div>
    </div>

    <div class="table-scroll">
      <p-table
        [value]="instruments()"
        [lazy]="true"
        (onLazyLoad)="onLazyLoad($event)"
        [loading]="loading()"
        [paginator]="true"
        [rows]="pageSize()"
        [totalRecords]="total()"
        [rowsPerPageOptions]="pageSizeOptions"
        [showCurrentPageReport]="true"
        [currentPageReportTemplate]="'table.pageReport' | transloco"
        sortField="symbol"
        [sortOrder]="1"
        dataKey="id"
        styleClass="p-datatable-sm"
      >
        <ng-template #header>
          <tr>
            <th pSortableColumn="symbol" style="min-width: 7rem">
              {{ 'admin.instruments.fields.symbol' | transloco }} <p-sortIcon field="symbol" />
            </th>
            <th pSortableColumn="name" style="min-width: 14rem">
              {{ 'admin.instruments.fields.name' | transloco }} <p-sortIcon field="name" />
            </th>
            <th pSortableColumn="exchange" style="min-width: 7rem">
              {{ 'admin.instruments.fields.exchange' | transloco }} <p-sortIcon field="exchange" />
            </th>
            <th pSortableColumn="currency" style="min-width: 6rem">
              {{ 'admin.instruments.fields.currency' | transloco }} <p-sortIcon field="currency" />
            </th>
            <th pSortableColumn="assetType" style="min-width: 7rem">
              {{ 'admin.instruments.fields.assetType' | transloco }}
              <p-sortIcon field="assetType" />
            </th>
            <th pSortableColumn="sector" style="min-width: 9rem">
              {{ 'admin.instruments.fields.sector' | transloco }} <p-sortIcon field="sector" />
            </th>
            <th pSortableColumn="active" style="min-width: 6rem">
              {{ 'admin.instruments.fields.status' | transloco }} <p-sortIcon field="active" />
            </th>
            <th style="width: 9rem"></th>
          </tr>
        </ng-template>

        <ng-template #body let-instrument>
          <tr [class.inactive-row]="!instrument.active">
            <td><span class="ticker">{{ instrument.symbol }}</span></td>
            <td>{{ instrument.name }}</td>
            <td>{{ instrument.exchange || '—' }}</td>
            <td>{{ instrument.currency }}</td>
            <td><p-tag [value]="instrument.assetType" severity="info" /></td>
            <td>{{ instrument.sector || '—' }}</td>
            <td>
              <p-tag
                [value]="
                  (instrument.active ? 'admin.instruments.active' : 'admin.instruments.inactive')
                    | transloco
                "
                [severity]="instrument.active ? 'success' : 'secondary'"
              />
            </td>
            <td>
              <div class="row-actions">
                <p-button
                  icon="pi pi-pencil"
                  [text]="true"
                  [rounded]="true"
                  [pTooltip]="'common.edit' | transloco"
                  tooltipPosition="bottom"
                  [ariaLabel]="'common.edit' | transloco"
                  (onClick)="openEdit(instrument)"
                />
                <p-button
                  [icon]="instrument.active ? 'pi pi-eye-slash' : 'pi pi-eye'"
                  [text]="true"
                  [rounded]="true"
                  [pTooltip]="
                    (instrument.active
                      ? 'admin.instruments.deactivate'
                      : 'admin.instruments.activate'
                    ) | transloco
                  "
                  tooltipPosition="bottom"
                  [ariaLabel]="
                    (instrument.active
                      ? 'admin.instruments.deactivate'
                      : 'admin.instruments.activate'
                    ) | transloco
                  "
                  (onClick)="setActive(instrument, !instrument.active)"
                />
                <p-button
                  icon="pi pi-trash"
                  severity="danger"
                  [text]="true"
                  [rounded]="true"
                  [pTooltip]="'common.delete' | transloco"
                  tooltipPosition="bottom"
                  [ariaLabel]="'common.delete' | transloco"
                  (onClick)="confirmDelete(instrument)"
                />
              </div>
            </td>
          </tr>
        </ng-template>

        <ng-template #emptymessage>
          <tr>
            <td colspan="8" class="muted">{{ 'admin.instruments.empty' | transloco }}</td>
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
      [style]="{ width: 'min(40rem, 95vw)' }"
      [header]="
        (editing() ? 'admin.instruments.editTitle' : 'admin.instruments.newTitle')
          | transloco: { symbol: editing()?.symbol ?? '' }
      "
    >
      <div class="dialog-form">
        <div class="field">
          <label for="f-symbol">{{ 'admin.instruments.fields.symbol' | transloco }}</label>
          <input
            pInputText
            id="f-symbol"
            [ngModel]="form().symbol"
            (ngModelChange)="patchForm({ symbol: $event })"
            [invalid]="!!form().symbol && !symbolValid()"
            autocomplete="off"
            autocapitalize="characters"
            spellcheck="false"
            maxlength="32"
          />
          @if (editing() && form().symbol.trim().toUpperCase() !== editing()?.symbol) {
            <small class="muted">{{ 'admin.instruments.renameHint' | transloco }}</small>
          }
        </div>
        <div class="field">
          <label for="f-name">{{ 'admin.instruments.fields.name' | transloco }}</label>
          <input
            pInputText
            id="f-name"
            [ngModel]="form().name"
            (ngModelChange)="patchForm({ name: $event })"
            autocomplete="off"
            maxlength="255"
          />
        </div>
        <div class="field">
          <label for="f-exchange">{{ 'admin.instruments.fields.exchange' | transloco }}</label>
          <input
            pInputText
            id="f-exchange"
            [ngModel]="form().exchange"
            (ngModelChange)="patchForm({ exchange: $event })"
            placeholder="NYSE"
            autocomplete="off"
            maxlength="64"
          />
        </div>
        <div class="field">
          <label for="f-currency">{{ 'admin.instruments.fields.currency' | transloco }}</label>
          <input
            pInputText
            id="f-currency"
            [ngModel]="form().currency"
            (ngModelChange)="patchForm({ currency: $event })"
            [invalid]="!currencyValid()"
            autocomplete="off"
            autocapitalize="characters"
            maxlength="3"
          />
        </div>
        <div class="field">
          <label for="f-type">{{ 'admin.instruments.fields.assetType' | transloco }}</label>
          <p-select
            inputId="f-type"
            [options]="assetTypeOptions"
            [ngModel]="form().assetType"
            (ngModelChange)="patchForm({ assetType: $event })"
            appendTo="body"
            [fluid]="true"
          />
        </div>
        <div class="field">
          <label for="f-sector">{{ 'admin.instruments.fields.sector' | transloco }}</label>
          <input
            pInputText
            id="f-sector"
            [ngModel]="form().sector"
            (ngModelChange)="patchForm({ sector: $event })"
            autocomplete="off"
            maxlength="128"
          />
        </div>
        <div class="field span-2 switch-field">
          <p-toggleswitch
            inputId="f-active"
            [ngModel]="form().active"
            (ngModelChange)="patchForm({ active: $event })"
          />
          <label for="f-active">{{ 'admin.instruments.fields.active' | transloco }}</label>
        </div>
        <p class="muted span-2 no-margin">{{ 'admin.instruments.activeHint' | transloco }}</p>
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
      titleKey="import.instruments.title"
      hintKey="import.instruments.hint"
      sampleFileName="instruments-sample.csv"
      exportBaseName="instruments"
      exportLabelKey="import.instruments.downloadData"
      exportHintKey="import.instruments.downloadDataHint"
      (imported)="onImported()"
    />
  `,
  styles: `
    .instruments-hint {
      margin-top: -0.5rem;
    }

    .filter-actions {
      flex-direction: row;
      align-items: flex-end;
      gap: 0.5rem;
    }

    .ticker {
      font-weight: 600;
      font-family: var(--app-mono-font, ui-monospace, monospace);
    }

    .inactive-row td {
      opacity: 0.6;
    }

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
export class InstrumentsPage {
  protected readonly api = inject(InstrumentApi);
  private readonly store = inject(InstrumentStore);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly transloco = inject(TranslocoService);

  protected readonly assetTypeOptions = [...ASSET_TYPES];

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly instruments = signal<Instrument[]>([]);
  protected readonly total = signal(0);
  protected readonly pageSize = signal(PAGE_SIZE_OPTIONS[0]);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly pageIndex = signal(0);
  private readonly sort = signal('symbol,asc');

  protected readonly draft = signal<Filters>(emptyFilters());
  private readonly applied = signal<Filters>(emptyFilters());

  protected readonly dialogOpen = signal(false);
  protected readonly importOpen = signal(false);
  protected readonly editing = signal<Instrument | null>(null);
  protected readonly form = signal<InstrumentForm>(emptyForm());

  /** Ticks on a language switch and when a translation file finishes loading, as in top-bar.ts. */
  private readonly i18nTick = toSignal(
    merge(this.transloco.langChanges$, this.transloco.events$),
    { initialValue: null },
  );

  protected readonly statusOptions = computed(() => {
    this.i18nTick();
    return [
      { value: 'all', label: this.transloco.translate('admin.instruments.anyStatus') },
      { value: 'active', label: this.transloco.translate('admin.instruments.active') },
      { value: 'inactive', label: this.transloco.translate('admin.instruments.inactive') },
    ];
  });

  protected readonly symbolValid = computed(() => SYMBOL_PATTERN.test(this.form().symbol.trim()));
  protected readonly currencyValid = computed(() =>
    CURRENCY_PATTERN.test(this.form().currency.trim()),
  );
  protected readonly formValid = computed(
    () => this.symbolValid() && this.currencyValid() && this.form().name.trim().length > 0,
  );

  protected patchDraft(patch: Partial<Filters>): void {
    this.draft.update((current) => ({ ...current, ...patch }));
  }

  protected patchForm(patch: Partial<InstrumentForm>): void {
    this.form.update((current) => ({ ...current, ...patch }));
  }

  protected onLazyLoad(event: LazyTableEvent): void {
    const rows = event.rows ?? this.pageSize();
    this.pageSize.set(rows);
    this.pageIndex.set(rows > 0 ? Math.floor((event.first ?? 0) / rows) : 0);
    this.sort.set(toSortParam(event, {}, 'symbol,asc'));
    this.reload();
  }

  protected apply(): void {
    this.applied.set({ ...this.draft() });
    this.pageIndex.set(0);
    this.reload();
  }

  protected reset(): void {
    this.draft.set(emptyFilters());
    this.applied.set(emptyFilters());
    this.pageIndex.set(0);
    this.reload();
  }

  protected reload(): void {
    const filters = this.applied();
    this.loading.set(true);
    this.api
      .list({
        search: filters.search.trim() || null,
        assetType: filters.assetType,
        exchange: filters.exchange.trim() || null,
        active: filters.status === 'all' ? null : filters.status === 'active',
        page: this.pageIndex(),
        size: this.pageSize(),
        sort: this.sort(),
      })
      .subscribe({
        next: (page) => {
          this.instruments.set(page.content);
          this.total.set(page.totalElements);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  protected openCreate(): void {
    this.editing.set(null);
    this.form.set(emptyForm());
    this.dialogOpen.set(true);
  }

  protected openEdit(instrument: Instrument): void {
    this.editing.set(instrument);
    this.form.set(toForm(instrument));
    this.dialogOpen.set(true);
  }

  protected save(): void {
    const body = toRequest(this.form());
    const current = this.editing();
    const request$ = current ? this.api.update(current.symbol, body) : this.api.create(body);

    this.saving.set(true);
    request$.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.dialogOpen.set(false);
        this.changed();
        this.toast('admin.instruments.saved', { symbol: saved.symbol });
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.reportConflict(error, 'admin.instruments.conflict');
      },
    });
  }

  protected setActive(instrument: Instrument, active: boolean): void {
    this.api.update(instrument.symbol, { ...toRequest(toForm(instrument)), active }).subscribe(() => {
      this.changed();
      this.toast(active ? 'admin.instruments.activated' : 'admin.instruments.deactivated', {
        symbol: instrument.symbol,
      });
    });
  }

  protected confirmDelete(instrument: Instrument): void {
    this.confirm.confirm({
      header: this.transloco.translate('admin.instruments.deleteTitle'),
      message: this.transloco.translate('admin.instruments.deleteMessage', {
        symbol: instrument.symbol,
      }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.transloco.translate('common.delete'),
      rejectLabel: this.transloco.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => this.remove(instrument),
    });
  }

  protected onImported(): void {
    this.changed();
  }

  /**
   * The global error interceptor passes 409 through untouched, so this page answers it. Here a
   * 409 means dividends reference the instrument - and the useful response is not an error toast
   * but the thing the administrator can do instead.
   */
  private remove(instrument: Instrument): void {
    this.api.remove(instrument.symbol).subscribe({
      next: () => {
        this.changed();
        this.toast('admin.instruments.deleted', { symbol: instrument.symbol });
      },
      error: (error: unknown) => {
        if (!(error instanceof HttpErrorResponse) || error.status !== 409) {
          return;
        }
        if (!instrument.active) {
          this.reportConflict(error, 'admin.instruments.inUse');
          return;
        }
        // Deferred a tick: the confirmation service is still closing the delete prompt.
        setTimeout(() =>
          this.confirm.confirm({
            header: this.transloco.translate('admin.instruments.inUse'),
            message: this.transloco.translate('admin.instruments.inUseMessage', {
              symbol: instrument.symbol,
            }),
            icon: 'pi pi-info-circle',
            acceptLabel: this.transloco.translate('admin.instruments.deactivate'),
            rejectLabel: this.transloco.translate('common.cancel'),
            accept: () => this.setActive(instrument, false),
          }),
        );
      },
    });
  }

  /** Every write lands here: the table refreshes and the pickers elsewhere stop serving stale data. */
  private changed(): void {
    this.store.invalidate();
    this.reload();
  }

  private reportConflict(error: unknown, summaryKey: string): void {
    if (error instanceof HttpErrorResponse && error.status === 409) {
      this.messages.add({
        severity: 'error',
        summary: this.transloco.translate(summaryKey),
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
