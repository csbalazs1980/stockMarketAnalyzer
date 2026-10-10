import { formatNumber } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  LOCALE_ID,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TooltipModule } from 'primeng/tooltip';
import { merge } from 'rxjs';

import { LanguageService } from '../../core/i18n/language.service';
import { ThemeService } from '../../core/theme/theme.service';
import { InflationApi } from '../inflation/inflation.api';
import {
  adjustPayments,
  buildInflationIndex,
  InflationIndex,
  latestInflationYear,
} from '../inflation/inflation-adjust';
import { DividendApi } from './dividend.api';
import { DEFAULT_VALUE_UNIT, Dividend, DividendQuery } from './dividend.models';
import { DividendMonthlyChart } from './dividend-monthly-chart';
import {
  buildMatrix,
  chartSeries,
  chartUnit,
  MonthColumn,
  MoneyCell,
} from './dividend-monthly.model';
import { sortInPlace } from '../../core/table/table-sort';

/** Which figure the cell prints; the other one goes in the tooltip. */
type DisplayMode = 'original' | 'converted';

/** How many payments a tooltip lists before it gives up and counts the rest. */
const TOOLTIP_ITEM_LIMIT = 10;

interface MonthlyFilters {
  from: string;
  to: string;
  /** Blank means every currency, which is the default. */
  unit: string;
  valueIn: string;
}

const EMPTY_FILTERS: MonthlyFilters = { from: '', to: '', unit: '', valueIn: DEFAULT_VALUE_UNIT };

interface ColumnView {
  key: string;
  year: number;
  label: string;
  startsYear: boolean;
}

interface CellView {
  key: string;
  startsYear: boolean;
  /** What to print, one line per currency. Empty when the month is empty. */
  lines: string[];
  tooltip: string;
  /** Nothing was paid here. */
  blank: boolean;
  /** Something was paid, but no rate converts it - an em dash, never a zero. */
  noRate: boolean;
  partial: boolean;
  count: number;
}

interface RowView {
  key: string;
  symbol: string | null;
  name: string | null;
  slot: number;
  cells: CellView[];
  total: CellView | null;
  /** What the Total column sorts by: the converted figure, else a single currency's amount. */
  sortTotal: number | null;
}

type PivotSortField = 'company' | 'total';

interface MatrixView {
  columns: ColumnView[];
  rows: RowView[];
  totals: CellView[];
  grandTotal: CellView;
  monthCount: number;
  paymentCount: number;
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/**
 * The month grid: what was received, by company and calendar month.
 *
 * Deliberately a separate route from the CRUD list rather than a mode on it. This one reads the
 * whole filtered range at once and pivots it client-side - a page boundary would punch a hole in
 * a month - and it never writes, so none of the dialogs, confirmations or lazy-load plumbing next
 * door belongs here.
 */
@Component({
  selector: 'app-dividend-monthly-page',
  imports: [
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    DialogModule,
    InputTextModule,
    SelectModule,
    SelectButtonModule,
    ToggleSwitchModule,
    TooltipModule,
    DividendMonthlyChart,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <h1>{{ 'dividends.monthly.title' | transloco }}</h1>
      <div class="row-actions">
        <p-button
          icon="pi pi-refresh"
          [text]="true"
          [ariaLabel]="'common.refresh' | transloco"
          (onClick)="reload()"
        />
        <p-button
          icon="pi pi-chart-bar"
          [label]="'dividends.monthly.chart' | transloco"
          [disabled]="view().monthCount === 0"
          (onClick)="chartOpen.set(true)"
        />
      </div>
    </div>

    <p class="muted monthly-hint">{{ 'dividends.monthly.hint' | transloco }}</p>

    <div class="filter-bar">
      <div class="field">
        <label for="m-from">{{ 'wealth.filters.from' | transloco }}</label>
        <input
          id="m-from"
          class="app-input"
          type="date"
          [ngModel]="draft().from"
          (ngModelChange)="patchDraft({ from: $event })"
        />
      </div>
      <div class="field">
        <label for="m-to">{{ 'wealth.filters.to' | transloco }}</label>
        <input
          id="m-to"
          class="app-input"
          type="date"
          [ngModel]="draft().to"
          (ngModelChange)="patchDraft({ to: $event })"
        />
      </div>
      <div class="field">
        <label for="m-unit">{{ 'dividends.fields.unit' | transloco }}</label>
        <p-select
          inputId="m-unit"
          [options]="unitOptions()"
          [ngModel]="draft().unit"
          (ngModelChange)="patchDraft({ unit: $event ?? '' })"
          optionLabel="label"
          optionValue="value"
          [showClear]="true"
          [editable]="true"
          appendTo="body"
          [placeholder]="'dividends.monthly.anyCurrency' | transloco"
        />
      </div>
      <div class="field">
        <label for="m-valuein">{{ 'wealth.filters.valueIn' | transloco }}</label>
        <input
          pInputText
          id="m-valuein"
          [ngModel]="draft().valueIn"
          (ngModelChange)="patchDraft({ valueIn: $event })"
          placeholder="HUF"
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

    <div class="view-bar">
      <div class="field">
        <label id="m-mode-label">{{ 'dividends.monthly.show' | transloco }}</label>
        <p-selectbutton
          [options]="modeOptions()"
          [ngModel]="mode()"
          (ngModelChange)="mode.set($event)"
          optionLabel="label"
          optionValue="value"
          [allowEmpty]="false"
          ariaLabelledBy="m-mode-label"
        />
      </div>
      <div class="field">
        <label id="m-layout-label">{{ 'dividends.monthly.layout' | transloco }}</label>
        <p-selectbutton
          [options]="layoutOptions()"
          [ngModel]="compact()"
          (ngModelChange)="compact.set($event)"
          optionLabel="label"
          optionValue="value"
          [allowEmpty]="false"
          ariaLabelledBy="m-layout-label"
        />
      </div>
      <div class="field">
        <label for="m-inflation">{{ 'dividends.monthly.inflationAdjusted' | transloco }}</label>
        <div class="switch-row">
          <p-toggleswitch
            inputId="m-inflation"
            [ngModel]="inflationAdjusted()"
            (ngModelChange)="setInflationAdjusted($event)"
            [disabled]="inflationLoading()"
          />
        </div>
      </div>
    </div>

    @if (inflationAdjusted() && adjustment(); as adjusted) {
      <p class="muted inflation-note">
        <i class="pi pi-percentage" aria-hidden="true"></i>
        {{
          'dividends.monthly.inflationNote'
            | transloco: { years: inflationYears(), valueUnit: valueUnit() }
        }}
      </p>
      @if (adjusted.unadjustedUnits.length > 0) {
        <p class="inflation-warning">
          <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>
          {{
            'dividends.monthly.inflationMissing'
              | transloco
                : {
                    units: adjusted.unadjustedUnits.join(', '),
                    count: adjusted.unadjustedAmounts + adjusted.unadjustedValues,
                  }
          }}
        </p>
      }
    }

    @if (loading()) {
      <p class="muted">{{ 'common.loading' | transloco }}</p>
    } @else if (view().monthCount === 0) {
      <p class="muted">{{ 'dividends.monthly.empty' | transloco }}</p>
    } @else {
      <div class="matrix-scroll">
        <table class="matrix">
          <thead>
            <tr>
              <th class="corner" [attr.aria-sort]="ariaSort('company')">
                @if (!compact()) {
                  <button type="button" class="sort-header" (click)="sortBy('company')">
                    {{ 'dividends.fields.instrument' | transloco }}
                    <i [class]="sortIcon('company')" aria-hidden="true"></i>
                  </button>
                }
              </th>
              @for (column of view().columns; track column.key) {
                <th class="month" [class.year-start]="column.startsYear" [title]="column.key">
                  @if (column.startsYear) {
                    <span class="year">{{ column.year }}</span>
                  }
                  <span class="month-name">{{ column.label }}</span>
                </th>
              }
              <th class="row-total" [attr.aria-sort]="ariaSort('total')">
                @if (compact()) {
                  {{ 'dividends.monthly.rowTotal' | transloco }}
                } @else {
                  <button type="button" class="sort-header" (click)="sortBy('total')">
                    {{ 'dividends.monthly.rowTotal' | transloco }}
                    <i [class]="sortIcon('total')" aria-hidden="true"></i>
                  </button>
                }
              </th>
            </tr>
          </thead>

          <tbody>
            @for (row of view().rows; track row.key) {
              <tr>
                <th class="company">
                  @if (row.symbol !== null) {
                    <span class="ticker">{{ row.symbol }}</span>
                    <span class="muted instrument-name">{{ row.name }}</span>
                  } @else {
                    <span class="muted slot">{{ row.slot }}</span>
                  }
                </th>

                @for (cell of row.cells; track cell.key) {
                  <td class="numeric cell" [class.year-start]="cell.startsYear">
                    @if (!cell.blank) {
                      <span
                        class="value"
                        [class.partial]="cell.partial"
                        [pTooltip]="cell.tooltip"
                        [escape]="false"
                        tooltipPosition="top"
                        [appendTo]="'body'"
                        tooltipStyleClass="dividend-tooltip"
                      >
                        @if (cell.noRate) {
                          <span class="muted">&mdash;</span>
                        } @else {
                          @for (line of cell.lines; track $index) {
                            <span class="line">{{ line }}</span>
                          }
                        }
                        @if (cell.count > 1) {
                          <span class="count" aria-hidden="true">{{ cell.count }}&times;</span>
                        }
                      </span>
                    }
                  </td>
                }

                <td class="numeric row-total">
                  @if (row.total && !row.total.blank) {
                    <span
                      class="value"
                      [class.partial]="row.total.partial"
                      [pTooltip]="row.total.tooltip"
                      [escape]="false"
                      tooltipPosition="left"
                      [appendTo]="'body'"
                      tooltipStyleClass="dividend-tooltip"
                    >
                      @if (row.total.noRate) {
                        <span class="muted">&mdash;</span>
                      } @else {
                        @for (line of row.total.lines; track $index) {
                          <span class="line">{{ line }}</span>
                        }
                      }
                    </span>
                  }
                </td>
              </tr>
            }
          </tbody>

          <tfoot>
            <tr>
              <th class="company">{{ 'dividends.monthly.monthTotal' | transloco }}</th>
              @for (total of view().totals; track total.key) {
                <td class="numeric cell" [class.year-start]="total.startsYear">
                  @if (!total.blank) {
                    <span
                      class="value"
                      [class.partial]="total.partial"
                      [pTooltip]="total.tooltip"
                      [escape]="false"
                      tooltipPosition="top"
                      [appendTo]="'body'"
                      tooltipStyleClass="dividend-tooltip"
                    >
                      @if (total.noRate) {
                        <span class="muted">&mdash;</span>
                      } @else {
                        @for (line of total.lines; track $index) {
                          <span class="line">{{ line }}</span>
                        }
                      }
                    </span>
                  }
                </td>
              }
              <td class="numeric row-total">
                @if (!view().grandTotal.blank) {
                  <span
                    class="value"
                    [class.partial]="view().grandTotal.partial"
                    [pTooltip]="view().grandTotal.tooltip"
                    [escape]="false"
                    tooltipPosition="left"
                    [appendTo]="'body'"
                    tooltipStyleClass="dividend-tooltip"
                  >
                    @if (view().grandTotal.noRate) {
                      <span class="muted">&mdash;</span>
                    } @else {
                      @for (line of view().grandTotal.lines; track $index) {
                        <span class="line">{{ line }}</span>
                      }
                    }
                  </span>
                }
              </td>
            </tr>
          </tfoot>
        </table>
      </div>

      <p class="muted matrix-footnote">
        {{
          'dividends.monthly.summary'
            | transloco: { payments: view().paymentCount, months: view().monthCount }
        }}
      </p>
    }

    <p-dialog
      [visible]="chartOpen()"
      (visibleChange)="chartOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(64rem, 95vw)' }"
      [header]="'dividends.monthly.chartTitle' | transloco"
    >
      @if (chartOpen()) {
        <app-dividend-monthly-chart
          [points]="chartPoints()"
          [unit]="chartAxisUnit()"
          [dark]="theme.dark()"
          [seriesLabel]="'dividends.monthly.chartSeries' | transloco"
          [partialLabel]="'dividends.partialTotal' | transloco"
        />
        <p class="muted chart-note">
          {{
            (appliedUnit() ? 'dividends.monthly.chartNoteFiltered' : 'dividends.monthly.chartNote')
              | transloco: { unit: chartAxisUnit() }
          }}
          @if (inflationAdjusted() && adjustment()) {
            {{ 'dividends.monthly.chartNoteInflation' | transloco: { years: inflationYears() } }}
          }
        </p>
      }
    </p-dialog>
  `,
  styles: `
    .monthly-hint {
      margin-top: -0.5rem;
    }

    .filter-actions {
      flex-direction: row;
      align-items: flex-end;
      gap: 0.5rem;
    }

    .switch-row {
      display: flex;
      align-items: center;
      min-height: 2.5rem;
    }

    .inflation-note,
    .inflation-warning {
      margin: -0.25rem 0 1rem;
      font-size: 0.85rem;
    }

    .inflation-warning {
      color: var(--p-orange-600, #c2410c);
    }

    .view-bar {
      display: flex;
      flex-wrap: wrap;
      gap: 1.25rem;
      margin-bottom: 1rem;
    }

    /* The grid is wide by nature: it scrolls inside itself rather than widening the page. */
    .matrix-scroll {
      overflow: auto;
      max-height: 70vh;
      border: 1px solid var(--p-content-border-color, #7a7a7a);
      border-radius: var(--p-content-border-radius, 6px);
    }

    table.matrix {
      border-collapse: separate;
      border-spacing: 0;
      font-size: 0.82rem;
      white-space: nowrap;
    }

    table.matrix th,
    table.matrix td {
      padding: 0.3rem 0.5rem;
      border-bottom: 1px solid var(--p-content-border-color, #7a7a7a);
      background: inherit;
    }

    table.matrix tr {
      background: var(--p-content-background, #bfbfbf);
    }

    table.matrix tbody tr:nth-child(even) {
      background: var(--app-row-striped);
    }

    /* Both the header and the first column stay put while the years scroll past. */
    table.matrix thead th {
      position: sticky;
      top: 0;
      z-index: 2;
      font-weight: 600;
      text-align: center;
      vertical-align: bottom;
    }

    /* A header that sorts: looks like the header text, behaves like a button. */
    .sort-header {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0;
      border: 0;
      background: none;
      color: inherit;
      font: inherit;
      cursor: pointer;
    }

    .sort-header i {
      font-size: 0.8rem;
      opacity: 0.75;
    }

    table.matrix th.company,
    table.matrix th.corner {
      position: sticky;
      left: 0;
      z-index: 3;
      text-align: left;
      font-weight: 500;
      min-width: 9rem;
      box-shadow: 1px 0 0 var(--p-content-border-color, #7a7a7a);
    }

    table.matrix thead th.corner {
      z-index: 4;
    }

    table.matrix .month .year {
      display: block;
      font-size: 0.72rem;
      opacity: 0.65;
    }

    table.matrix .month-name {
      display: block;
    }

    table.matrix .year-start {
      border-left: 2px solid var(--p-content-border-color, #7a7a7a);
    }

    table.matrix .cell {
      min-width: 5.5rem;
    }

    table.matrix .row-total {
      min-width: 8rem;
      font-weight: 600;
      border-left: 2px solid var(--p-content-border-color, #7a7a7a);
    }

    table.matrix tfoot tr {
      position: sticky;
      bottom: 0;
      z-index: 2;
      background: var(--p-content-background, #bfbfbf);
      font-weight: 600;
    }

    table.matrix tfoot th.company {
      z-index: 3;
    }

    table.matrix tfoot td,
    table.matrix tfoot th {
      border-top: 2px solid var(--p-content-border-color, #7a7a7a);
      border-bottom: none;
    }

    .value {
      display: inline-flex;
      flex-direction: column;
      align-items: flex-end;
      cursor: help;
    }

    .value .line {
      display: block;
    }

    .value.partial {
      text-decoration: underline dotted;
      text-underline-offset: 0.2rem;
    }

    .value .count {
      font-size: 0.68rem;
      opacity: 0.55;
    }

    .ticker {
      font-weight: 600;
    }

    .instrument-name {
      margin-inline-start: 0.4rem;
      font-size: 0.85em;
    }

    .slot {
      font-size: 0.75rem;
    }

    .matrix-footnote,
    .chart-note {
      margin-top: 0.75rem;
      font-size: 0.85rem;
    }
  `,
})
export class DividendMonthlyPage {
  private readonly api = inject(DividendApi);
  private readonly inflationApi = inject(InflationApi);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);
  private readonly locale = inject(LOCALE_ID);
  protected readonly theme = inject(ThemeService);

  protected readonly loading = signal(false);
  protected readonly mode = signal<DisplayMode>('original');
  protected readonly compact = signal(false);

  /** Show every figure in the money of the latest year with an inflation figure. Off by default. */
  protected readonly inflationAdjusted = signal(false);
  protected readonly inflationLoading = signal(false);
  /** The stored rates, fetched the first time the toggle is switched on and then kept. */
  private readonly inflationIndex = signal<InflationIndex | null>(null);

  /**
   * Company A-Z by default. Only the by-company layout sorts: in compact mode a row is a slot with
   * no identity of its own, so there is nothing to order it by.
   */
  protected readonly pivotSort = signal<{ field: PivotSortField; order: 1 | -1 }>({
    field: 'company',
    order: 1,
  });

  /** Same column again flips the direction; a new column starts ascending, as p-table does. */
  protected sortBy(field: PivotSortField): void {
    this.pivotSort.update((current) =>
      current.field === field
        ? { field, order: current.order === 1 ? -1 : 1 }
        : { field, order: 1 },
    );
  }

  protected sortIcon(field: PivotSortField): string {
    const sort = this.pivotSort();
    if (sort.field !== field) {
      return 'pi pi-sort-alt';
    }
    return sort.order === 1 ? 'pi pi-sort-amount-up-alt' : 'pi pi-sort-amount-down';
  }

  protected ariaSort(field: PivotSortField): 'ascending' | 'descending' | 'none' {
    const sort = this.pivotSort();
    if (this.compact() || sort.field !== field) {
      return 'none';
    }
    return sort.order === 1 ? 'ascending' : 'descending';
  }

  private sortRows(rows: RowView[], compact: boolean): RowView[] {
    if (compact) {
      return rows;
    }
    const { field, order } = this.pivotSort();
    const sorted = [...rows];
    sortInPlace(
      sorted,
      field === 'company' ? (row) => row.symbol : (row) => row.sortTotal,
      order,
    );
    return sorted;
  }
  protected readonly chartOpen = signal(false);

  /**
   * Turning the toggle on loads the rates once; turning it off just stops applying them. A failed
   * load (toasted by the error interceptor) switches the toggle back off rather than leaving it on
   * over unadjusted figures.
   */
  protected setInflationAdjusted(on: boolean): void {
    this.inflationAdjusted.set(on);
    if (!on || this.inflationIndex() !== null) {
      return;
    }
    this.inflationLoading.set(true);
    // The table is a few currencies times a few decades; one page of the API's maximum size holds it.
    this.inflationApi.list({ size: 1000, sort: 'year,asc' }).subscribe({
      next: (page) => {
        this.inflationIndex.set(buildInflationIndex(page.content));
        this.inflationLoading.set(false);
      },
      error: () => {
        this.inflationLoading.set(false);
        this.inflationAdjusted.set(false);
      },
    });
  }

  protected readonly draft = signal<MonthlyFilters>({ ...EMPTY_FILTERS });
  private readonly applied = signal<MonthlyFilters>({ ...EMPTY_FILTERS });

  private readonly rows = signal<Dividend[]>([]);

  /**
   * The currencies the picker offers, learned from the last *unfiltered* fetch. Deriving them
   * from the rows on screen would shrink the list to whatever is already selected, and then there
   * would be no way back to the others.
   */
  private readonly knownUnits = signal<string[]>([]);

  /** Re-render the imperatively translated bits whenever a language is switched or loaded. */
  private readonly i18nTick = toSignal(
    merge(this.transloco.langChanges$, this.transloco.events$),
    { initialValue: null },
  );

  protected readonly appliedUnit = computed(() => this.applied().unit.trim().toUpperCase());

  protected readonly valueUnit = computed(
    () => this.applied().valueIn.trim().toUpperCase() || DEFAULT_VALUE_UNIT,
  );

  /**
   * The payments with both figures inflation-adjusted, or null when the toggle is off (or the
   * rates have not arrived yet). Adjusting the rows *before* the pivot is what makes every cell,
   * total, tooltip and the chart follow without each of them knowing about inflation.
   */
  protected readonly adjustment = computed(() => {
    const index = this.inflationIndex();
    if (!this.inflationAdjusted() || index === null) {
      return null;
    }
    return adjustPayments(this.rows(), index);
  });

  /** 'HUF 2025 · USD 2024' - which year each involved currency is adjusted through. */
  protected readonly inflationYears = computed(() => {
    const index = this.inflationIndex();
    if (index === null) {
      return '';
    }
    const units = new Set(this.rows().map((row) => row.unit.toUpperCase()));
    units.add(this.valueUnit());
    return [...units]
      .sort()
      .map((unit) => ({ unit, year: latestInflationYear(index, unit) }))
      .filter((entry) => entry.year !== null)
      .map((entry) => `${entry.unit} ${entry.year}`)
      .join(' · ');
  });

  private readonly matrix = computed(() => {
    const filters = this.applied();
    return buildMatrix(this.adjustment()?.rows ?? this.rows(), {
      from: filters.from || null,
      to: filters.to || null,
      valueUnit: filters.valueIn.trim().toUpperCase() || DEFAULT_VALUE_UNIT,
      compact: this.compact(),
    });
  });

  protected readonly chartPoints = computed(() =>
    chartSeries(this.matrix(), this.appliedUnit() || null),
  );

  protected readonly chartAxisUnit = computed(() =>
    chartUnit(this.matrix(), this.appliedUnit() || null),
  );

  protected readonly unitOptions = computed(() => {
    this.i18nTick();
    return this.knownUnits().map((unit) => ({ label: unit, value: unit }));
  });

  protected readonly modeOptions = computed(() => {
    this.i18nTick();
    return [
      { label: this.transloco.translate('dividends.monthly.showOriginal'), value: 'original' },
      { label: this.transloco.translate('dividends.monthly.showConverted'), value: 'converted' },
    ];
  });

  protected readonly layoutOptions = computed(() => {
    this.i18nTick();
    return [
      { label: this.transloco.translate('dividends.monthly.byCompany'), value: false },
      { label: this.transloco.translate('dividends.monthly.compact'), value: true },
    ];
  });

  /** Short month names in the active language - 'nov', 'okt', the way the spreadsheet reads. */
  private readonly monthNames = computed(() => {
    const format = new Intl.DateTimeFormat(this.language.active(), { month: 'short' });
    return Array.from({ length: 12 }, (_unused, month) =>
      format.format(new Date(Date.UTC(2001, month, 1))),
    );
  });

  /**
   * Everything the template prints, rendered once per (data, mode, layout, language) change
   * rather than per change-detection pass. A grid this wide is the one place in the app where
   * that difference is visible.
   */
  protected readonly view = computed<MatrixView>(() => {
    const matrix = this.matrix();
    const mode = this.mode();
    const compact = this.compact();
    const names = this.monthNames();
    const adjusted = this.adjustment() !== null;
    this.i18nTick();

    const toView = (cell: MoneyCell | null, column: MonthColumn | null, slot: number): CellView => {
      const key = column?.key ?? `total-${slot}`;
      if (cell === null) {
        return {
          key,
          startsYear: column?.startsYear ?? false,
          lines: [],
          tooltip: '',
          blank: true,
          noRate: false,
          partial: false,
          count: 0,
        };
      }
      return {
        key,
        startsYear: column?.startsYear ?? false,
        lines: this.linesFor(cell, mode),
        tooltip: this.tooltipFor(cell, mode, compact && column !== null, adjusted),
        blank: false,
        noRate: mode === 'converted' && cell.converted === null,
        partial: mode === 'converted' && cell.partial,
        count: cell.count,
      };
    };

    return {
      columns: matrix.columns.map((column) => ({
        key: column.key,
        year: column.year,
        label: names[column.month - 1],
        startsYear: column.startsYear,
      })),
      rows: this.sortRows(
        matrix.rows.map((row, index) => ({
          key: row.key,
          symbol: row.symbol,
          name: row.name,
          slot: index + 1,
          cells: row.cells.map(({ column, cell }) => toView(cell, column, 0)),
          total: row.total === null ? null : toView(row.total, null, index),
          sortTotal:
            row.total === null
              ? null
              : (row.total.converted ??
                (row.total.originals.length === 1 ? row.total.originals[0].amount : null)),
        })),
        compact,
      ),
      totals: matrix.totals.map(({ column, cell }) => toView(cell, column, 0)),
      grandTotal: toView(matrix.grandTotal, null, -1),
      monthCount: matrix.columns.length,
      paymentCount: matrix.count,
    };
  });

  constructor() {
    this.reload();
  }

  protected patchDraft(patch: Partial<MonthlyFilters>): void {
    this.draft.update((current) => ({ ...current, ...patch }));
  }

  protected apply(): void {
    this.applied.set({ ...this.draft() });
    this.reload();
  }

  protected reset(): void {
    this.draft.set({ ...EMPTY_FILTERS });
    this.applied.set({ ...EMPTY_FILTERS });
    this.reload();
  }

  protected reload(): void {
    const filters = this.applied();
    const unit = filters.unit.trim().toUpperCase();
    const query: DividendQuery = {
      from: filters.from || null,
      to: filters.to || null,
      unit: unit || null,
      valueIn: filters.valueIn.trim().toUpperCase() || DEFAULT_VALUE_UNIT,
    };

    this.loading.set(true);
    this.api.listAll(query).subscribe({
      next: (dividends) => {
        this.rows.set(dividends);
        if (!unit) {
          this.knownUnits.set([...new Set(dividends.map((row) => row.unit))].sort());
        }
        this.loading.set(false);
      },
      error: () => {
        this.rows.set([]);
        this.loading.set(false);
      },
    });
  }

  private amount(value: number, digits: string): string {
    return formatNumber(value, this.locale, digits);
  }

  private money(value: number, unit: string, digits = '1.0-2'): string {
    return `${this.amount(value, digits)} ${unit}`;
  }

  /** The figure the cell prints: the amounts as received, or the one converted number. */
  private linesFor(cell: MoneyCell, mode: DisplayMode): string[] {
    if (mode === 'original') {
      return cell.originals.map((money) => this.money(money.amount, money.unit));
    }
    if (cell.converted === null) {
      return [];
    }
    const total = this.money(cell.converted, cell.valueUnit);
    return [cell.partial ? `≥ ${total}` : total];
  }

  /**
   * The tooltip carries whichever half the cell is not showing, so switching mode swaps the two
   * rather than hiding anything. In compact mode it also carries the company, which is the whole
   * price of dropping the per-company rows.
   */
  private tooltipFor(
    cell: MoneyCell,
    mode: DisplayMode,
    wantsCompany: boolean,
    inflationAdjusted: boolean,
  ): string {
    const parts: string[] = [];

    // A footer total mixes companies, so the name belongs on each line rather than at the top.
    const oneCompany = new Set(cell.items.map((item) => item.instrumentId)).size === 1;

    if (wantsCompany && oneCompany) {
      const first = cell.items[0];
      parts.push(
        `<div class="tt-company"><b>${escapeHtml(first.instrumentSymbol)}</b> ` +
          `<span>${escapeHtml(first.instrumentName)}</span></div>`,
      );
    }

    const flipped =
      mode === 'original'
        ? cell.converted === null
          ? this.transloco.translate('wealth.noRate')
          : (cell.partial ? '≥ ' : '') + this.money(cell.converted, cell.valueUnit)
        : cell.originals.map((money) => this.money(money.amount, money.unit)).join(' + ');
    parts.push(`<div class="tt-main">${escapeHtml(flipped)}</div>`);

    if (cell.partial) {
      parts.push(
        `<div class="tt-note">${escapeHtml(this.transloco.translate('dividends.partialTotal'))}</div>`,
      );
    }
    if (inflationAdjusted) {
      parts.push(
        `<div class="tt-note">${escapeHtml(
          this.transloco.translate('dividends.monthly.inflationTooltip'),
        )}</div>`,
      );
    }

    const shown = cell.items.slice(0, TOOLTIP_ITEM_LIMIT);
    const lines = shown.map((item) => {
      const converted =
        item.currentValue === null ? '—' : this.money(item.currentValue, cell.valueUnit);
      const symbol = oneCompany ? '' : `${escapeHtml(item.instrumentSymbol)} · `;
      return (
        `<div>${item.paymentDate} · ${symbol}` +
        `${escapeHtml(this.money(item.amount, item.unit, '1.0-6'))} · ` +
        `${escapeHtml(converted)}</div>`
      );
    });
    if (cell.items.length > shown.length) {
      lines.push(
        `<div>${escapeHtml(
          this.transloco.translate('dividends.monthly.andMore', {
            count: cell.items.length - shown.length,
          }),
        )}</div>`,
      );
    }
    parts.push(`<div class="tt-list">${lines.join('')}</div>`);

    return parts.join('');
  }
}
