import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TooltipModule } from 'primeng/tooltip';

import { LazyTableEvent, toSortParam } from '../../core/table/table-sort';
import { CsvImportDialog } from '../../shared/csv-import-dialog';
import { InstrumentOption } from '../instruments/instrument.models';
import { InstrumentStore } from '../instruments/instrument.store';
import { DividendApi } from './dividend.api';
import {
  DEFAULT_VALUE_UNIT,
  Dividend,
  DividendQuery,
  DividendRequest,
  DividendSummary,
} from './dividend.models';
import { PAGE_SIZE_OPTIONS } from '../../core/table/page-size';

interface DividendFilters {
  from: string;
  to: string;
  instrumentId: number | null;
  unit: string;
  valueIn: string;
}

interface DividendForm {
  instrumentId: number | null;
  amount: number | null;
  unit: string;
  paymentDate: string;
}

const EMPTY_FILTERS: DividendFilters = {
  from: '',
  to: '',
  instrumentId: null,
  unit: '',
  valueIn: DEFAULT_VALUE_UNIT,
};

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function emptyForm(): DividendForm {
  return { instrumentId: null, amount: null, unit: '', paymentDate: today() };
}

@Component({
  selector: 'app-dividends-page',
  imports: [
    DecimalPipe,
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    CsvImportDialog,
    DialogModule,
    InputNumberModule,
    InputTextModule,
    SelectModule,
    TableModule,
    ToggleSwitchModule,
    TooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <h1>{{ 'dividends.title' | transloco }}</h1>
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
          [label]="'dividends.new' | transloco"
          (onClick)="openCreate()"
        />
      </div>
    </div>

    <p class="muted dividends-hint">{{ 'dividends.hint' | transloco }}</p>

    <div class="filter-bar">
      <div class="field">
        <label for="d-from">{{ 'wealth.filters.from' | transloco }}</label>
        <input
          id="d-from"
          class="app-input"
          type="date"
          [ngModel]="draft().from"
          (ngModelChange)="patchDraft({ from: $event })"
        />
      </div>
      <div class="field">
        <label for="d-to">{{ 'wealth.filters.to' | transloco }}</label>
        <input
          id="d-to"
          class="app-input"
          type="date"
          [ngModel]="draft().to"
          (ngModelChange)="patchDraft({ to: $event })"
        />
      </div>
      <div class="field">
        <label for="d-instrument">{{ 'dividends.fields.instrument' | transloco }}</label>
        <p-select
          inputId="d-instrument"
          [options]="instrumentOptions()"
          [ngModel]="draft().instrumentId"
          (ngModelChange)="patchDraft({ instrumentId: $event })"
          optionLabel="label"
          optionValue="value"
          [showClear]="true"
          [filter]="true"
          filterBy="label"
          [virtualScroll]="true"
          [virtualScrollItemSize]="38"
          appendTo="body"
          [placeholder]="'dividends.filters.anyInstrument' | transloco"
        />
      </div>
      <div class="field">
        <label for="d-unit">{{ 'dividends.fields.unit' | transloco }}</label>
        <input
          pInputText
          id="d-unit"
          [ngModel]="draft().unit"
          (ngModelChange)="patchDraft({ unit: $event })"
          placeholder="USD"
        />
      </div>
      <div class="field">
        <label for="d-valuein">{{ 'wealth.filters.valueIn' | transloco }}</label>
        <input
          pInputText
          id="d-valuein"
          [ngModel]="draft().valueIn"
          (ngModelChange)="patchDraft({ valueIn: $event })"
          placeholder="HUF"
        />
      </div>
      <div class="field">
        <label for="d-inflation">{{ 'dividends.monthly.inflationAdjusted' | transloco }}</label>
        <div class="switch-row">
          <p-toggleswitch
            inputId="d-inflation"
            [ngModel]="inflationAdjusted()"
            (ngModelChange)="setInflationAdjusted($event)"
          />
        </div>
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

    @if (inflationAdjusted()) {
      <p class="muted inflation-note">
        <i class="pi pi-percentage" aria-hidden="true"></i>
        {{ 'dividends.inflationNote' | transloco: { valueUnit: valueUnit() } }}
      </p>
    }

    <div class="table-scroll">
      <p-table
        [value]="dividends()"
        [lazy]="true"
        (onLazyLoad)="onLazyLoad($event)"
        [loading]="loading()"
        [paginator]="true"
        [rows]="pageSize()"
        [totalRecords]="totalRecords()"
        [rowsPerPageOptions]="pageSizeOptions"
        [showCurrentPageReport]="true"
        [currentPageReportTemplate]="'table.pageReport' | transloco"
        sortField="paymentDate"
        [sortOrder]="-1"
        dataKey="id"
        styleClass="p-datatable-sm"
      >
        <ng-template #header>
          <tr>
            <th pSortableColumn="instrument" style="min-width: 14rem">
              {{ 'dividends.fields.instrument' | transloco }} <p-sortIcon field="instrument" />
            </th>
            <th pSortableColumn="amount" class="numeric" style="min-width: 9rem">
              {{ 'dividends.fields.amount' | transloco }} <p-sortIcon field="amount" />
            </th>
            <th pSortableColumn="unit" style="min-width: 5rem">
              {{ 'dividends.fields.unit' | transloco }} <p-sortIcon field="unit" />
            </th>
            <th pSortableColumn="paymentDate" style="min-width: 9rem">
              {{ 'dividends.fields.paymentDate' | transloco }} <p-sortIcon field="paymentDate" />
            </th>
            <th pSortableColumn="value" class="numeric" style="min-width: 11rem">
              {{ 'wealth.fields.value' | transloco }} <p-sortIcon field="value" />
            </th>
            <th style="width: 7rem"></th>
          </tr>
        </ng-template>

        <ng-template #body let-dividend>
          <tr>
            <td>
              <span class="ticker">{{ dividend.instrumentSymbol }}</span>
              <span class="muted instrument-name">{{ dividend.instrumentName }}</span>
            </td>
            <td class="numeric">
              @if (dividend.inflationAdjusted; as adjusted) {
                @if (!adjusted.complete) {
                  <i
                    class="pi pi-exclamation-triangle not-adjusted"
                    [pTooltip]="'dividends.notInflationAdjusted' | transloco"
                    tooltipPosition="top"
                  ></i>
                }
                <span
                  [pTooltip]="
                    'dividends.asEntered'
                      | transloco: { amount: (dividend.amount | number: '1.0-6'), unit: dividend.unit }
                  "
                  tooltipPosition="top"
                  >{{ adjusted.amount | number: '1.0-6' }}</span
                >
              } @else {
                {{ dividend.amount | number: '1.0-6' }}
              }
            </td>
            <td>{{ dividend.unit }}</td>
            <td>{{ dividend.paymentDate }}</td>
            <td class="numeric">
              @if (dividend.currentValue === null) {
                <span class="muted" [title]="'wealth.noRate' | transloco">&mdash;</span>
              } @else if (dividend.inflationAdjusted?.currentValue != null) {
                <span
                  [pTooltip]="
                    'dividends.asEntered'
                      | transloco
                        : {
                            amount: (dividend.currentValue | number: '1.0-2'),
                            unit: dividend.valueUnit,
                          }
                  "
                  tooltipPosition="top"
                  >{{ dividend.inflationAdjusted?.currentValue | number: '1.0-2' }}</span
                >
                <span class="muted">{{ dividend.valueUnit }}</span>
              } @else {
                {{ dividend.currentValue | number: '1.0-2' }}
                <span class="muted">{{ dividend.valueUnit }}</span>
              }
            </td>
            <td>
              <div class="row-actions">
                <p-button
                  icon="pi pi-pencil"
                  [text]="true"
                  [rounded]="true"
                  [ariaLabel]="'common.edit' | transloco"
                  (onClick)="openEdit(dividend)"
                />
                <p-button
                  icon="pi pi-trash"
                  severity="danger"
                  [text]="true"
                  [rounded]="true"
                  [ariaLabel]="'common.delete' | transloco"
                  (onClick)="confirmDelete(dividend)"
                />
              </div>
            </td>
          </tr>
        </ng-template>

        <ng-template #footer>
          @if (summary(); as totals) {
            <tr class="totals-row">
              <td colspan="4">
                {{ 'dividends.total' | transloco: { count: totals.count } }}
              </td>
              <td class="numeric">
                @if (totals.total === null) {
                  <span class="muted" [title]="'wealth.noRate' | transloco">&mdash;</span>
                } @else {
                  <span
                    [class.partial]="totals.totalPartial"
                    [title]="totals.totalPartial ? ('dividends.partialTotal' | transloco) : ''"
                  >
                    @if (totals.totalPartial) {
                      <span aria-hidden="true">&ge;&nbsp;</span>
                    }
                    {{ totals.total | number: '1.0-2' }}
                    <span class="muted">{{ totals.valueUnit }}</span>
                  </span>
                }
                @if (totals.inflationAdjusted && totals.notInflationAdjusted) {
                  <i
                    class="pi pi-exclamation-triangle not-adjusted"
                    [pTooltip]="
                      'dividends.notInflationAdjustedTotal'
                        | transloco: { count: totals.notInflationAdjusted }
                    "
                    tooltipPosition="top"
                  ></i>
                }
              </td>
              <td></td>
            </tr>
          }
        </ng-template>

        <ng-template #emptymessage>
          <tr>
            <td colspan="6" class="muted">{{ 'dividends.empty' | transloco }}</td>
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
      [style]="{ width: 'min(38rem, 95vw)' }"
      [header]="
        (editingId() === null ? 'dividends.newTitle' : 'dividends.editTitle') | transloco
      "
    >
      <div class="dialog-form">
        <div class="field span-2">
          <label for="f-instrument">{{ 'dividends.fields.instrument' | transloco }}</label>
          <p-select
            inputId="f-instrument"
            [options]="instrumentOptions()"
            [ngModel]="form().instrumentId"
            (ngModelChange)="onInstrumentChange($event)"
            optionLabel="label"
            optionValue="value"
            [filter]="true"
            filterBy="label"
            [virtualScroll]="true"
            [virtualScrollItemSize]="38"
            appendTo="body"
            [placeholder]="'dividends.form.pickInstrument' | transloco"
          />
          @if (instrumentOptions().length === 0) {
            <small class="muted">{{ 'dividends.form.noInstruments' | transloco }}</small>
          }
        </div>

        <div class="field">
          <label for="f-amount">{{ 'dividends.fields.amount' | transloco }}</label>
          <p-inputnumber
            inputId="f-amount"
            [ngModel]="form().amount"
            (ngModelChange)="patchForm({ amount: $event })"
            [minFractionDigits]="0"
            [maxFractionDigits]="6"
          />
        </div>

        <div class="field">
          <label for="f-unit">{{ 'dividends.fields.unit' | transloco }}</label>
          <input
            pInputText
            id="f-unit"
            [ngModel]="form().unit"
            (ngModelChange)="onUnitInput($event)"
            placeholder="USD"
            autocomplete="off"
          />
          <small class="muted">{{ 'dividends.form.unitHint' | transloco }}</small>
        </div>

        <div class="field">
          <label for="f-paid">{{ 'dividends.fields.paymentDate' | transloco }}</label>
          <input
            id="f-paid"
            class="app-input"
            type="date"
            [ngModel]="form().paymentDate"
            (ngModelChange)="patchForm({ paymentDate: $event })"
          />
        </div>
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
      titleKey="import.dividends.title"
      hintKey="import.dividends.hint"
      sampleFileName="dividends-sample.csv"
      exportBaseName="dividends"
      (imported)="reload()"
    />
  `,
  styles: `
    .dividends-hint {
      margin-top: -0.5rem;
    }

    .filter-actions {
      flex-direction: row;
      align-items: flex-end;
      gap: 0.5rem;
    }

    .ticker {
      font-weight: 600;
    }

    .instrument-name {
      margin-inline-start: 0.5rem;
      font-size: 0.85em;
    }

    .switch-row {
      display: flex;
      align-items: center;
      min-height: 2.5rem;
    }

    .inflation-note {
      margin: -0.25rem 0 0.75rem;
      font-size: 0.85rem;
    }

    .not-adjusted {
      color: var(--p-orange-600, #c2410c);
      font-size: 0.8rem;
      margin-inline: 0.35rem;
      cursor: help;
    }

    .totals-row td {
      font-weight: 600;
    }

    .partial {
      text-decoration: underline dotted;
      text-underline-offset: 0.2rem;
      cursor: help;
    }
  `,
})
export class DividendsPage {
  /** Protected rather than private: the import dialog takes it as an input from the template. */
  protected readonly api = inject(DividendApi);
  private readonly instrumentStore = inject(InstrumentStore);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly transloco = inject(TranslocoService);

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly dividends = signal<Dividend[]>([]);
  protected readonly summary = signal<DividendSummary | null>(null);
  protected readonly totalRecords = signal(0);
  protected readonly pageSize = signal(PAGE_SIZE_OPTIONS[0]);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly pageIndex = signal(0);
  private readonly sort = signal('paymentDate,desc');

  /**
   * Show amounts and values in the money of the latest year with an inflation figure. Computed by
   * the backend, because this table pages, sorts and totals there: adjusting only the page on
   * screen would leave the footer total unadjusted and let page 2 hold bigger figures than page 1.
   * Takes effect immediately, without Apply - it changes how rows are shown, not which.
   */
  protected readonly inflationAdjusted = signal(false);

  protected readonly valueUnit = computed(
    () => this.applied().valueIn.trim().toUpperCase() || DEFAULT_VALUE_UNIT,
  );

  protected setInflationAdjusted(on: boolean): void {
    this.inflationAdjusted.set(on);
    this.reload();
  }

  private readonly instruments = signal<InstrumentOption[]>([]);

  protected readonly draft = signal<DividendFilters>({ ...EMPTY_FILTERS });
  private readonly applied = signal<DividendFilters>({ ...EMPTY_FILTERS });

  protected readonly dialogOpen = signal(false);
  protected readonly importOpen = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly form = signal<DividendForm>(emptyForm());

  /** Once the unit has been typed by hand, picking an instrument stops overwriting it. */
  private readonly unitTouched = signal(false);

  protected readonly instrumentOptions = computed(() =>
    this.instruments().map((instrument) => ({
      label: `${instrument.symbol} - ${instrument.name}`,
      value: instrument.id,
    })),
  );

  protected readonly formValid = computed(() => {
    const value = this.form();
    return (
      value.instrumentId !== null &&
      value.amount !== null &&
      value.amount >= 0 &&
      value.unit.trim().length > 0
    );
  });

  constructor() {
    this.loadInstruments();
    // The table's own onLazyLoad fires on init and loads the first page, so no reload() here.
  }

  protected patchDraft(patch: Partial<DividendFilters>): void {
    this.draft.update((current) => ({ ...current, ...patch }));
  }

  protected patchForm(patch: Partial<DividendForm>): void {
    this.form.update((current) => ({ ...current, ...patch }));
  }

  protected onUnitInput(value: string): void {
    this.unitTouched.set(true);
    this.patchForm({ unit: value.toUpperCase() });
  }

  /**
   * A dividend is usually paid in whatever the instrument trades in, so the currency is
   * prefilled from it - but only as a prefill. A broker converting a US payout into HUF before
   * it lands is the normal case, not an anomaly, so the field stays editable and a hand-typed
   * unit is never overwritten.
   */
  protected onInstrumentChange(instrumentId: number | null): void {
    this.patchForm({ instrumentId });

    if (this.unitTouched() || instrumentId === null) {
      return;
    }
    const instrument = this.instruments().find((candidate) => candidate.id === instrumentId);
    if (instrument) {
      this.patchForm({ unit: instrument.currency });
    }
  }

  /**
   * p-table fires this on init and on every paginator interaction. Typed structurally rather
   * than importing TableLazyLoadEvent, which PrimeNG 21 publishes from 'primeng/types/table'
   * rather than 'primeng/table'.
   */
  protected onLazyLoad(event: LazyTableEvent): void {
    const rows = event.rows ?? this.pageSize();
    this.pageSize.set(rows);
    this.pageIndex.set(rows > 0 ? Math.floor((event.first ?? 0) / rows) : 0);
    // The ticker is a join. 'value' is not a column at all: the backend recognises it, values every
    // matching row and sorts in memory, so the order is right across pages too.
    this.sort.set(toSortParam(event, { instrument: 'instrument.symbol' }, 'paymentDate,desc'));
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
    const query = this.query();

    this.loading.set(true);
    this.api.list(query).subscribe({
      next: (page) => {
        this.dividends.set(page.content);
        this.totalRecords.set(page.totalElements);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });

    // Asked for separately, and deliberately not awaited: the table should not sit empty
    // waiting on a figure that only decorates it.
    this.api.summary(query).subscribe({
      next: (summary) => this.summary.set(summary),
      error: () => this.summary.set(null),
    });
  }

  protected openCreate(): void {
    this.editingId.set(null);
    this.unitTouched.set(false);
    this.form.set(emptyForm());
    this.dialogOpen.set(true);
  }

  protected openEdit(dividend: Dividend): void {
    this.editingId.set(dividend.id);
    // Changing the instrument on an existing row must never silently redenominate it.
    this.unitTouched.set(true);
    this.form.set({
      instrumentId: dividend.instrumentId,
      amount: dividend.amount,
      unit: dividend.unit,
      paymentDate: dividend.paymentDate,
    });
    this.dialogOpen.set(true);
  }

  protected save(): void {
    const value = this.form();
    if (value.instrumentId === null || value.amount === null) {
      return;
    }

    const body: DividendRequest = {
      instrumentId: value.instrumentId,
      amount: value.amount,
      unit: value.unit.trim().toUpperCase(),
      paymentDate: value.paymentDate || null,
    };

    const id = this.editingId();
    const request$ = id === null ? this.api.create(body) : this.api.update(id, body);

    this.saving.set(true);
    request$.subscribe({
      next: () => {
        this.saving.set(false);
        this.dialogOpen.set(false);
        this.toast('dividends.saved');
        this.reload();
      },
      error: () => this.saving.set(false),
    });
  }

  protected confirmDelete(dividend: Dividend): void {
    this.confirm.confirm({
      header: this.transloco.translate('dividends.deleteTitle'),
      message: this.transloco.translate('dividends.deleteMessage', {
        symbol: dividend.instrumentSymbol,
        date: dividend.paymentDate,
      }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.transloco.translate('common.delete'),
      rejectLabel: this.transloco.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      accept: () =>
        this.api.remove(dividend.id).subscribe(() => {
          this.toast('dividends.deleted');
          this.reload();
        }),
    });
  }

  private query(): DividendQuery {
    const filters = this.applied();
    return {
      from: filters.from || null,
      to: filters.to || null,
      instrumentId: filters.instrumentId,
      unit: filters.unit || null,
      valueIn: filters.valueIn || DEFAULT_VALUE_UNIT,
      inflationAdjusted: this.inflationAdjusted() || null,
      page: this.pageIndex(),
      size: this.pageSize(),
      sort: this.sort(),
    };
  }

  /**
   * The whole universe, from the shared cache - a picker that stops at page one hides whatever
   * sorts late in the alphabet. Repeat visits to this page cost no request.
   */
  private loadInstruments(): void {
    this.instrumentStore.options().subscribe({
      next: (instruments) => this.instruments.set(instruments),
      error: () => this.instruments.set([]),
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
