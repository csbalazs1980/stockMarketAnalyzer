import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
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
import { TooltipModule } from 'primeng/tooltip';

import { problemMessage } from '../../core/http/problem-detail';
import { PAGE_SIZE_OPTIONS } from '../../core/table/page-size';
import { LazyTableEvent, toSortParam } from '../../core/table/table-sort';
import { CsvImportDialog } from '../../shared/csv-import-dialog';
import { InflationApi } from '../inflation/inflation.api';
import {
  INFLATION_RATE_MAX,
  INFLATION_RATE_MIN,
  INFLATION_YEAR_MAX,
  INFLATION_YEAR_MIN,
  InflationFetchResponse,
  InflationFetchUnitResult,
  InflationRate,
  InflationRateRequest,
} from '../inflation/inflation.models';

/** Same shape as the backend's `Units.normalise` accepts: a short code, no spaces. */
const UNIT_PATTERN = /^[A-Za-z0-9._-]{1,16}$/;

const DEFAULT_SORT = 'year,desc';

interface Filters {
  unit: string | null;
  fromYear: number | null;
  toYear: number | null;
}

function emptyFilters(): Filters {
  return { unit: null, fromYear: null, toYear: null };
}

interface InflationForm {
  unit: string;
  year: number | null;
  rate: number | null;
}

/** A new figure most often belongs to the year that has just ended. */
function emptyForm(unit: string | null): InflationForm {
  return { unit: unit ?? 'HUF', year: new Date().getFullYear() - 1, rate: null };
}

function toRequest(form: InflationForm): InflationRateRequest {
  return {
    unit: form.unit.trim().toUpperCase(),
    year: form.year ?? 0,
    rate: form.rate ?? 0,
  };
}

/**
 * Inflation administration: one annual figure per currency and year, in percentage points.
 *
 * <p>Writes go to `/api/v1/inflation-rates`, whose POST/PUT/DELETE already require ADMIN; the
 * `adminGuard` on the route only decides whether the screen is worth showing.
 *
 * <p><strong>New is an upsert, edit is not.</strong> Creating a figure for a (currency, year)
 * that already has one replaces it - the way a provisional estimate is superseded by the final
 * print, and the same thing the CSV import does. Editing goes by id, so the currency and year can
 * be corrected too; moving a row onto a pair another row holds is refused with a 409, which this
 * page reports itself because the global error interceptor passes 409 through untouched.
 */
@Component({
  selector: 'app-inflation-rates-page',
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
    TooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <h1>{{ 'admin.inflation.title' | transloco }}</h1>
      <div class="row-actions">
        <p-button
          icon="pi pi-refresh"
          [text]="true"
          [ariaLabel]="'common.refresh' | transloco"
          (onClick)="reload()"
        />
        <p-button
          icon="pi pi-cloud-download"
          [label]="'admin.inflation.fetch' | transloco"
          severity="secondary"
          [outlined]="true"
          [loading]="fetching()"
          [pTooltip]="'admin.inflation.fetchHint' | transloco"
          tooltipPosition="bottom"
          (onClick)="fetchLatest()"
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
          [label]="'admin.inflation.new' | transloco"
          (onClick)="openCreate()"
        />
      </div>
    </div>

    <p class="muted inflation-hint">{{ 'admin.inflation.hint' | transloco }}</p>

    <div class="filter-bar">
      <div class="field">
        <label for="inf-unit">{{ 'admin.inflation.fields.unit' | transloco }}</label>
        <p-select
          inputId="inf-unit"
          [options]="units()"
          [ngModel]="draft().unit"
          (ngModelChange)="patchDraft({ unit: $event })"
          [showClear]="true"
          appendTo="body"
          [placeholder]="'admin.inflation.anyUnit' | transloco"
        />
      </div>
      <div class="field year-field">
        <label for="inf-from">{{ 'admin.inflation.fromYear' | transloco }}</label>
        <p-inputnumber
          inputId="inf-from"
          [ngModel]="draft().fromYear"
          (ngModelChange)="patchDraft({ fromYear: $event })"
          [useGrouping]="false"
          [min]="yearMin"
          [max]="yearMax"
          (keyup.enter)="apply()"
        />
      </div>
      <div class="field year-field">
        <label for="inf-to">{{ 'admin.inflation.toYear' | transloco }}</label>
        <p-inputnumber
          inputId="inf-to"
          [ngModel]="draft().toYear"
          (ngModelChange)="patchDraft({ toYear: $event })"
          [useGrouping]="false"
          [min]="yearMin"
          [max]="yearMax"
          (keyup.enter)="apply()"
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
        sortField="year"
        [sortOrder]="-1"
        dataKey="id"
        styleClass="p-datatable-sm"
      >
        <ng-template #header>
          <tr>
            <th pSortableColumn="unit" style="min-width: 7rem">
              {{ 'admin.inflation.fields.unit' | transloco }} <p-sortIcon field="unit" />
            </th>
            <th pSortableColumn="year" style="min-width: 6rem">
              {{ 'admin.inflation.fields.year' | transloco }} <p-sortIcon field="year" />
            </th>
            <th pSortableColumn="rate" class="numeric" style="min-width: 8rem">
              {{ 'admin.inflation.fields.rate' | transloco }} <p-sortIcon field="rate" />
            </th>
            <th style="width: 7rem"></th>
          </tr>
        </ng-template>

        <ng-template #body let-rate>
          <tr>
            <td><span class="unit">{{ rate.unit }}</span></td>
            <td>{{ rate.year }}</td>
            <td class="numeric" [class.deflation]="rate.rate < 0">
              {{ rate.rate | number: '1.1-4' }} %
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
                  (onClick)="openEdit(rate)"
                />
                <p-button
                  icon="pi pi-trash"
                  severity="danger"
                  [text]="true"
                  [rounded]="true"
                  [pTooltip]="'common.delete' | transloco"
                  tooltipPosition="bottom"
                  [ariaLabel]="'common.delete' | transloco"
                  (onClick)="confirmDelete(rate)"
                />
              </div>
            </td>
          </tr>
        </ng-template>

        <ng-template #emptymessage>
          <tr>
            <td colspan="4" class="muted">{{ 'admin.inflation.empty' | transloco }}</td>
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
      [style]="{ width: 'min(32rem, 95vw)' }"
      [header]="
        (editing() ? 'admin.inflation.editTitle' : 'admin.inflation.newTitle')
          | transloco: { unit: editing()?.unit ?? '', year: editing()?.year ?? '' }
      "
    >
      <div class="dialog-form">
        <div class="field">
          <label for="f-unit">{{ 'admin.inflation.fields.unit' | transloco }}</label>
          <input
            pInputText
            id="f-unit"
            [ngModel]="form().unit"
            (ngModelChange)="patchForm({ unit: $event })"
            [invalid]="!!form().unit && !unitValid()"
            autocomplete="off"
            autocapitalize="characters"
            spellcheck="false"
            maxlength="16"
          />
        </div>
        <div class="field">
          <label for="f-year">{{ 'admin.inflation.fields.year' | transloco }}</label>
          <p-inputnumber
            inputId="f-year"
            [ngModel]="form().year"
            (ngModelChange)="patchForm({ year: $event })"
            [useGrouping]="false"
            [min]="yearMin"
            [max]="yearMax"
          />
        </div>
        <div class="field span-2">
          <label for="f-rate">{{ 'admin.inflation.fields.rate' | transloco }}</label>
          <p-inputnumber
            inputId="f-rate"
            [ngModel]="form().rate"
            (ngModelChange)="patchForm({ rate: $event })"
            [minFractionDigits]="0"
            [maxFractionDigits]="4"
            [min]="rateMin"
            [max]="rateMax"
            suffix=" %"
          />
        </div>
        <p class="muted span-2 no-margin">
          {{ (editing() ? 'admin.inflation.editHint' : 'admin.inflation.newHint') | transloco }}
        </p>
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
      titleKey="import.inflation.title"
      hintKey="import.inflation.hint"
      sampleFileName="inflation-rates-sample.csv"
      exportBaseName="inflation-rates"
      exportLabelKey="import.inflation.downloadData"
      exportHintKey="import.inflation.downloadDataHint"
      (imported)="onImported()"
    />
  `,
  styles: `
    .inflation-hint {
      margin-top: -0.5rem;
    }

    .filter-actions {
      flex-direction: row;
      align-items: flex-end;
      gap: 0.5rem;
    }

    .year-field {
      max-width: 8rem;
    }

    .unit {
      font-weight: 600;
      font-family: var(--app-mono-font, ui-monospace, monospace);
    }

    .numeric {
      text-align: right;
      font-variant-numeric: tabular-nums;
    }

    .deflation {
      color: var(--p-blue-500);
    }

    .no-margin {
      margin: 0;
      font-size: 0.85rem;
    }
  `,
})
export class InflationRatesPage {
  protected readonly api = inject(InflationApi);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly transloco = inject(TranslocoService);

  protected readonly yearMin = INFLATION_YEAR_MIN;
  protected readonly yearMax = INFLATION_YEAR_MAX;
  protected readonly rateMin = INFLATION_RATE_MIN;
  protected readonly rateMax = INFLATION_RATE_MAX;

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly fetching = signal(false);
  protected readonly rates = signal<InflationRate[]>([]);
  protected readonly units = signal<string[]>([]);
  protected readonly total = signal(0);
  protected readonly pageSize = signal(PAGE_SIZE_OPTIONS[0]);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  private readonly pageIndex = signal(0);
  private readonly sort = signal(DEFAULT_SORT);

  protected readonly draft = signal<Filters>(emptyFilters());
  private readonly applied = signal<Filters>(emptyFilters());

  protected readonly dialogOpen = signal(false);
  protected readonly importOpen = signal(false);
  protected readonly editing = signal<InflationRate | null>(null);
  protected readonly form = signal<InflationForm>(emptyForm(null));

  protected readonly unitValid = computed(() => UNIT_PATTERN.test(this.form().unit.trim()));
  protected readonly formValid = computed(() => {
    const { year, rate } = this.form();
    return (
      this.unitValid() &&
      year !== null &&
      year >= INFLATION_YEAR_MIN &&
      year <= INFLATION_YEAR_MAX &&
      rate !== null &&
      rate >= INFLATION_RATE_MIN &&
      rate <= INFLATION_RATE_MAX
    );
  });

  constructor() {
    this.loadUnits();
  }

  protected patchDraft(patch: Partial<Filters>): void {
    this.draft.update((current) => ({ ...current, ...patch }));
  }

  protected patchForm(patch: Partial<InflationForm>): void {
    this.form.update((current) => ({ ...current, ...patch }));
  }

  protected onLazyLoad(event: LazyTableEvent): void {
    const rows = event.rows ?? this.pageSize();
    this.pageSize.set(rows);
    this.pageIndex.set(rows > 0 ? Math.floor((event.first ?? 0) / rows) : 0);
    this.sort.set(toSortParam(event, {}, DEFAULT_SORT));
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
        unit: filters.unit,
        fromYear: filters.fromYear,
        toYear: filters.toYear,
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

  protected openCreate(): void {
    this.editing.set(null);
    // Prefill the currency being looked at, so a run of entries for one currency is quick.
    this.form.set(emptyForm(this.applied().unit));
    this.dialogOpen.set(true);
  }

  protected openEdit(rate: InflationRate): void {
    this.editing.set(rate);
    this.form.set({ unit: rate.unit, year: rate.year, rate: rate.rate });
    this.dialogOpen.set(true);
  }

  protected save(): void {
    const body = toRequest(this.form());
    const current = this.editing();
    const request$ = current ? this.api.update(current.id, body) : this.api.create(body);

    this.saving.set(true);
    request$.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.dialogOpen.set(false);
        this.changed();
        this.toast('admin.inflation.saved', { unit: saved.unit, year: saved.year });
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.reportConflict(error);
      },
    });
  }

  protected confirmDelete(rate: InflationRate): void {
    this.confirm.confirm({
      header: this.transloco.translate('admin.inflation.deleteTitle'),
      message: this.transloco.translate('admin.inflation.deleteMessage', {
        unit: rate.unit,
        year: rate.year,
      }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.transloco.translate('common.delete'),
      rejectLabel: this.transloco.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      accept: () =>
        this.api.remove(rate.id).subscribe(() => {
          this.changed();
          this.toast('admin.inflation.deleted', { unit: rate.unit, year: rate.year });
        }),
    });
  }

  /**
   * One toast for the whole press: a summary line, and one line per currency saying which years
   * were added or why none were. 502s (source unreachable) are toasted by the error interceptor.
   */
  protected fetchLatest(): void {
    this.fetching.set(true);
    this.api.fetchLatest().subscribe({
      next: (response) => {
        this.fetching.set(false);
        if (response.stored > 0) {
          this.changed();
        }
        this.messages.add({
          severity: response.stored > 0 ? 'success' : 'info',
          summary: this.transloco.translate(
            response.stored > 0 ? 'admin.inflation.fetched' : 'admin.inflation.fetchedNone',
            { count: response.stored, source: response.source },
          ),
          detail: this.fetchDetail(response),
          life: 8000,
        });
      },
      error: () => this.fetching.set(false),
    });
  }

  private fetchDetail(response: InflationFetchResponse): string {
    return response.units.map((unit) => this.fetchLine(unit)).join(' · ');
  }

  private fetchLine(result: InflationFetchUnitResult): string {
    if (result.stored.length > 0) {
      const first = result.stored[0].year;
      const last = result.stored[result.stored.length - 1].year;
      return this.transloco.translate('admin.inflation.fetchedUnit', {
        unit: result.unit,
        years: first === last ? `${first}` : `${first}–${last}`,
      });
    }
    if (result.latestAvailableYear === null) {
      return this.transloco.translate('admin.inflation.fetchedUnitNoData', { unit: result.unit });
    }
    return this.transloco.translate('admin.inflation.fetchedUnitUpToDate', {
      unit: result.unit,
      year: result.previousLatestYear ?? result.latestAvailableYear,
    });
  }

  protected onImported(): void {
    this.changed();
  }

  /** Every write lands here: the table and the currency filter both refresh. */
  private changed(): void {
    this.reload();
    this.loadUnits();
  }

  private loadUnits(): void {
    this.api.units().subscribe((units) => this.units.set(units));
  }

  private reportConflict(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 409) {
      this.messages.add({
        severity: 'error',
        summary: this.transloco.translate('admin.inflation.conflict'),
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
