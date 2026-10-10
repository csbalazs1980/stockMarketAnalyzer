import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ConfirmationService, MessageService, TreeNode } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TreeTableModule } from 'primeng/treetable';
import { merge } from 'rxjs';

import { ThemeService } from '../../core/theme/theme.service';
import { CsvImportDialog } from '../../shared/csv-import-dialog';
import { ExchangeRateApi } from '../wealth/wealth.api';
import { ExpensePieChart } from './expense-pie-chart';
import { ExpenseCategoryApi, ExpenseItemApi } from './expense.api';
import {
  AS_ENTERED,
  CUSTOM_RECURRENCE,
  CategoryGroup,
  RECURRENCE_PRESETS,
  RateTable,
  UnitTotal,
  buildRateTable,
  displayUnit,
  groupByCategory,
  perPeriodIn,
  PERIOD_DAYS,
  presetById,
  presetIdFor,
  shareBreakdown,
  totalsByUnit,
  unitsInRates,
} from './expense-recurrence';
import {
  ExpenseCategory,
  ExpenseItem,
  ExpenseItemRequest,
  ExpenseView,
  RECURRENCE_PERIODS,
  RecurrencePeriod,
} from './expense.models';
import { SortFunctionEvent, sortInPlace } from '../../core/table/table-sort';
import { PAGE_SIZE_OPTIONS } from '../../core/table/page-size';

/** One row of either table. The tree needs a single shape for parents and children alike. */
interface ExpenseRow {
  kind: 'group' | 'item';
  label: string;
  /** Null on a group row. */
  item: ExpenseItem | null;
  /** Per-currency subtotals, on a group row. */
  totals: UnitTotal[];
  /** How many expenses the group holds, counting inactive ones. */
  count: number;
}

interface ItemForm {
  name: string;
  amount: number | null;
  unit: string;
  /** A preset id, or CUSTOM_RECURRENCE to reveal the interval and period fields. */
  preset: string;
  interval: number;
  period: RecurrencePeriod;
  startDate: string;
  expiryDate: string;
  categoryIds: number[];
}

function emptyForm(): ItemForm {
  return {
    name: '',
    amount: null,
    unit: '',
    preset: 'monthly',
    interval: 1,
    period: 'MONTHLY',
    startDate: '',
    expiryDate: '',
    categoryIds: [],
  };
}

/**
 * The recurring-expense list: CRUD, a raw view and a collected-by-category view, and totals
 * normalised to whichever period the user picks.
 *
 * <p>Two decisions shape the whole component.
 *
 * <p><strong>The rows are fetched once, whole, and everything else happens here.</strong> Both the
 * totals and the grouped view have to see every matching row: a figure computed from one page
 * would change as you paged, which is worse than showing none, and a category whose items
 * straddled a page boundary would simply be wrong. A personal list of bills is tens of rows, so
 * the whole set is the right unit of work - the same reasoning as the wealth tree being built
 * client-side from one `size=500` page. The filter bar therefore needs no Apply button either.
 *
 * <p><strong>Normalisation is arithmetic, not currency conversion.</strong> Amounts are summed per
 * currency rather than converted into one, unlike the wealth and dividend screens. Those answer
 * "what is all of this worth together", which needs rates; a budget read per currency does not,
 * and running bills through a rate that moves daily would make last month's total change when
 * nothing did. The maths itself lives in expense-recurrence.ts, which is pure.
 */
@Component({
  selector: 'app-expense-items-page',
  imports: [
    DecimalPipe,
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    CsvImportDialog,
    DialogModule,
    ExpensePieChart,
    InputNumberModule,
    InputTextModule,
    MultiSelectModule,
    SelectModule,
    SelectButtonModule,
    TableModule,
    TagModule,
    ToggleSwitchModule,
    TreeTableModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <h1>{{ 'expenses.items.title' | transloco }}</h1>
      <div class="row-actions">
        <p-button
          icon="pi pi-refresh"
          [text]="true"
          [ariaLabel]="'common.refresh' | transloco"
          (onClick)="reload()"
        />
        <p-button
          icon="pi pi-chart-pie"
          [label]="'expenses.chart.button' | transloco"
          severity="secondary"
          [outlined]="true"
          [disabled]="activeCount() === 0"
          (onClick)="chartOpen.set(true)"
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
          [label]="'expenses.items.new' | transloco"
          (onClick)="openCreate()"
        />
      </div>
    </div>

    <p class="muted page-hint">{{ 'expenses.items.hint' | transloco }}</p>

    <div class="filter-bar">
      <div class="field">
        <label for="i-search">{{ 'expenses.filters.search' | transloco }}</label>
        <input
          pInputText
          id="i-search"
          [ngModel]="search()"
          (ngModelChange)="search.set($event)"
        />
      </div>
      <div class="field">
        <label for="i-unit">{{ 'expenses.fields.unit' | transloco }}</label>
        <p-select
          inputId="i-unit"
          [options]="unitOptions()"
          [ngModel]="unitFilter()"
          (ngModelChange)="unitFilter.set($event)"
          optionLabel="label"
          optionValue="value"
          [showClear]="true"
          appendTo="body"
          [placeholder]="'expenses.filters.anyUnit' | transloco"
        />
      </div>
      <div class="field">
        <label for="i-category">{{ 'expenses.fields.categories' | transloco }}</label>
        <p-select
          inputId="i-category"
          [options]="categoryFilterOptions()"
          [ngModel]="categoryFilter()"
          (ngModelChange)="categoryFilter.set($event)"
          optionLabel="label"
          optionValue="value"
          [showClear]="true"
          [filter]="true"
          filterBy="label"
          appendTo="body"
          [placeholder]="'expenses.filters.anyCategory' | transloco"
        />
      </div>
      <div class="field switch-field">
        <p-toggleswitch
          inputId="i-active"
          [ngModel]="activeOnly()"
          (ngModelChange)="activeOnly.set($event)"
        />
        <label for="i-active">{{ 'expenses.filters.activeOnly' | transloco }}</label>
      </div>
    </div>

    <div class="view-bar">
      <div class="field">
        <label id="i-view-label">{{ 'expenses.view.label' | transloco }}</label>
        <p-selectbutton
          [options]="viewOptions()"
          [ngModel]="view()"
          (ngModelChange)="view.set($event)"
          optionLabel="label"
          optionValue="value"
          [allowEmpty]="false"
          ariaLabelledBy="i-view-label"
        />
      </div>
      <div class="field">
        <label for="i-period">{{ 'expenses.totals.showPer' | transloco }}</label>
        <p-select
          inputId="i-period"
          [options]="periodOptions()"
          [ngModel]="totalsPeriod()"
          (ngModelChange)="totalsPeriod.set($event)"
          optionLabel="label"
          optionValue="value"
          appendTo="body"
        />
      </div>
      <div class="field">
        <label for="i-valuein">{{ 'expenses.totals.showIn' | transloco }}</label>
        <p-select
          inputId="i-valuein"
          [options]="valueInOptions()"
          [ngModel]="valueIn()"
          (ngModelChange)="valueIn.set($event)"
          optionLabel="label"
          optionValue="value"
          appendTo="body"
        />
        @if (converting()) {
          <small class="muted">{{ 'expenses.totals.rateHint' | transloco }}</small>
        }
      </div>
      <div class="totals-box">
        <span class="totals-label">{{
          'expenses.totals.perLabel' | transloco: { period: periodNoun() }
        }}</span>
        @if (grandTotals().length === 0) {
          <span class="muted" [title]="'expenses.noRate' | transloco">&mdash;</span>
        } @else {
          <span class="totals-values">
            @for (total of grandTotals(); track total.unit) {
              <span
                class="totals-value"
                [class.partial]="total.partial"
                [title]="total.partial ? ('expenses.totals.partial' | transloco) : ''"
              >
                @if (total.partial) {
                  <span aria-hidden="true">&ge;&nbsp;</span>
                }
                {{ total.total | number: '1.0-2' }} <span class="muted">{{ total.unit }}</span>
              </span>
            }
          </span>
        }
        <span class="muted totals-note">{{
          'expenses.totals.note' | transloco: { counted: activeCount(), total: filtered().length }
        }}</span>
      </div>
    </div>

    @if (view() === 'list') {
      <div class="table-scroll">
        <p-table
          [value]="filtered()"
          [loading]="loading()"
          [paginator]="filtered().length > pageSizeOptions[0]"
          [rows]="pageSizeOptions[0]"
          [rowsPerPageOptions]="pageSizeOptions"
          [showCurrentPageReport]="true"
          [currentPageReportTemplate]="'table.pageReport' | transloco"
          [customSort]="true"
          (sortFunction)="sortItems($event)"
          sortField="name"
          [sortOrder]="1"
          dataKey="id"
          styleClass="p-datatable-sm"
        >
          <ng-template #header>
            <tr>
              <th pSortableColumn="name" style="min-width: 13rem">
                {{ 'expenses.fields.name' | transloco }} <p-sortIcon field="name" />
              </th>
              <th pSortableColumn="amount" class="numeric" style="min-width: 9rem">
                {{ 'expenses.fields.amount' | transloco }} <p-sortIcon field="amount" />
              </th>
              <th pSortableColumn="recurrence" style="min-width: 9rem">
                {{ 'expenses.fields.recurrence' | transloco }} <p-sortIcon field="recurrence" />
              </th>
              <th pSortableColumn="perPeriod" class="numeric" style="min-width: 10rem">
                {{ 'expenses.totals.perColumn' | transloco: { period: periodNoun() } }}
                <p-sortIcon field="perPeriod" />
              </th>
              <th pSortableColumn="categories" style="min-width: 13rem">
                {{ 'expenses.fields.categories' | transloco }} <p-sortIcon field="categories" />
              </th>
              <th pSortableColumn="activePeriod" style="min-width: 11rem">
                {{ 'expenses.fields.activePeriod' | transloco }}
                <p-sortIcon field="activePeriod" />
              </th>
              <th style="width: 7rem"></th>
            </tr>
          </ng-template>

          <ng-template #body let-item>
            <tr [class.finished]="!item.active">
              <td>
                <span>{{ item.name }}</span>
                @if (!item.active) {
                  <span class="muted inactive-note">{{
                    'expenses.items.inactive' | transloco
                  }}</span>
                }
              </td>
              <td class="numeric">
                {{ item.amount | number: '1.0-6' }} <span class="muted">{{ item.unit }}</span>
              </td>
              <td>{{ recurrenceLabel(item) }}</td>
              <td class="numeric">
                <!-- Not "as value": a free plan legitimately costs 0, and 0 is falsy. -->
                @if (normalised(item) !== null) {
                  {{ normalised(item) | number: '1.0-2' }}
                  <span class="muted">{{ rowUnit(item) }}</span>
                } @else {
                  <span class="muted" [title]="'expenses.noRate' | transloco">&mdash;</span>
                }
              </td>
              <td>
                @if (item.categories.length === 0) {
                  <span class="muted">{{ 'expenses.items.untagged' | transloco }}</span>
                } @else {
                  <div class="tag-list">
                    @for (category of item.categories; track category.id) {
                      <p-tag
                        [value]="category.category"
                        [severity]="category.global ? 'info' : 'secondary'"
                      />
                    }
                  </div>
                }
              </td>
              <td>{{ rangeLabel(item) }}</td>
              <td>
                <div class="row-actions">
                  <p-button
                    icon="pi pi-pencil"
                    [text]="true"
                    [rounded]="true"
                    [ariaLabel]="'common.edit' | transloco"
                    (onClick)="openEdit(item)"
                  />
                  <p-button
                    icon="pi pi-trash"
                    severity="danger"
                    [text]="true"
                    [rounded]="true"
                    [ariaLabel]="'common.delete' | transloco"
                    (onClick)="confirmDelete(item)"
                  />
                </div>
              </td>
            </tr>
          </ng-template>

          <ng-template #emptymessage>
            <tr>
              <td colspan="7" class="muted">{{ 'expenses.items.empty' | transloco }}</td>
            </tr>
          </ng-template>

          <ng-template #summary>
            <span class="muted table-count">
              {{ 'table.rowCount' | transloco: { count: filtered().length } }}
            </span>
          </ng-template>
        </p-table>
      </div>
    } @else {
      <p class="muted grouped-note">{{ 'expenses.view.groupedNote' | transloco }}</p>

      <div class="table-scroll">
        <p-treetable
          [value]="tree()"
          [loading]="loading()"
          [customSort]="true"
          (sortFunction)="sortTree($event)"
          sortField="name"
          [sortOrder]="1"
          styleClass="p-treetable-sm"
        >
          <ng-template #header>
            <tr>
              <th ttSortableColumn="name" style="min-width: 16rem">
                {{ 'expenses.fields.categories' | transloco }} <p-treeTableSortIcon field="name" />
              </th>
              <th ttSortableColumn="amount" class="numeric" style="min-width: 9rem">
                {{ 'expenses.fields.amount' | transloco }} <p-treeTableSortIcon field="amount" />
              </th>
              <th ttSortableColumn="recurrence" style="min-width: 9rem">
                {{ 'expenses.fields.recurrence' | transloco }}
                <p-treeTableSortIcon field="recurrence" />
              </th>
              <th ttSortableColumn="perPeriod" class="numeric" style="min-width: 12rem">
                {{ 'expenses.totals.perColumn' | transloco: { period: periodNoun() } }}
                <p-treeTableSortIcon field="perPeriod" />
              </th>
              <th style="width: 7rem"></th>
            </tr>
          </ng-template>

          <ng-template #body let-rowNode let-rowData="rowData">
            <tr [class.finished]="rowData.kind === 'item' && !rowData.item.active">
              <td>
                <p-treeTableToggler [rowNode]="rowNode" />
                <span [class.group-label]="rowData.kind === 'group'">{{ rowData.label }}</span>
                @if (rowData.kind === 'group') {
                  <span class="muted group-count">{{
                    'expenses.view.groupCount' | transloco: { count: rowData.count }
                  }}</span>
                }
              </td>
              <td class="numeric">
                @if (rowData.kind === 'item') {
                  {{ rowData.item.amount | number: '1.0-6' }}
                  <span class="muted">{{ rowData.item.unit }}</span>
                }
              </td>
              <td>
                @if (rowData.kind === 'item') {
                  {{ recurrenceLabel(rowData.item) }}
                }
              </td>
              <td class="numeric">
                @if (rowData.kind === 'item') {
                  @if (normalised(rowData.item) !== null) {
                    {{ normalised(rowData.item) | number: '1.0-2' }}
                    <span class="muted">{{ rowUnit(rowData.item) }}</span>
                  } @else {
                    <span class="muted" [title]="'expenses.noRate' | transloco">&mdash;</span>
                  }
                } @else if (rowData.totals.length === 0) {
                  <span class="muted" [title]="'expenses.noRate' | transloco">&mdash;</span>
                } @else {
                  <span class="group-totals">
                    @for (total of rowData.totals; track total.unit) {
                      <span
                        class="group-total"
                        [class.partial]="total.partial"
                        [title]="total.partial ? ('expenses.totals.partial' | transloco) : ''"
                        >@if (total.partial) {
                          <span aria-hidden="true">&ge;&nbsp;</span>
                        }{{ total.total | number: '1.0-2' }}
                        <span class="muted">{{ total.unit }}</span></span
                      >
                    }
                  </span>
                }
              </td>
              <td>
                @if (rowData.kind === 'item') {
                  <div class="row-actions">
                    <p-button
                      icon="pi pi-pencil"
                      [text]="true"
                      [rounded]="true"
                      [ariaLabel]="'common.edit' | transloco"
                      (onClick)="openEdit(rowData.item)"
                    />
                    <p-button
                      icon="pi pi-trash"
                      severity="danger"
                      [text]="true"
                      [rounded]="true"
                      [ariaLabel]="'common.delete' | transloco"
                      (onClick)="confirmDelete(rowData.item)"
                    />
                  </div>
                }
              </td>
            </tr>
          </ng-template>

          <ng-template #emptymessage>
            <tr>
              <td colspan="5" class="muted">{{ 'expenses.items.empty' | transloco }}</td>
            </tr>
          </ng-template>

          <ng-template #summary>
            <span class="muted table-count">
              {{
                'table.groupSummary' | transloco: { groups: tree().length, items: filtered().length }
              }}
            </span>
          </ng-template>
        </p-treetable>
      </div>
    }

    <p-dialog
      [visible]="dialogOpen()"
      (visibleChange)="dialogOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(42rem, 95vw)' }"
      [header]="
        (editingId() === null ? 'expenses.items.newTitle' : 'expenses.items.editTitle') | transloco
      "
    >
      <div class="dialog-form">
        <div class="field span-2">
          <label for="f-name">{{ 'expenses.fields.name' | transloco }}</label>
          <input
            pInputText
            id="f-name"
            [ngModel]="form().name"
            (ngModelChange)="patchForm({ name: $event })"
            autocomplete="off"
          />
        </div>

        <div class="field">
          <label for="f-amount">{{ 'expenses.fields.amount' | transloco }}</label>
          <p-inputnumber
            inputId="f-amount"
            [ngModel]="form().amount"
            (ngModelChange)="patchForm({ amount: $event })"
            [minFractionDigits]="0"
            [maxFractionDigits]="6"
          />
        </div>

        <div class="field">
          <label for="f-unit">{{ 'expenses.fields.unit' | transloco }}</label>
          <input
            pInputText
            id="f-unit"
            [ngModel]="form().unit"
            (ngModelChange)="onUnitInput($event)"
            placeholder="HUF"
            autocomplete="off"
          />
        </div>

        <div class="field" [class.span-2]="!custom()">
          <label for="f-preset">{{ 'expenses.fields.recurrence' | transloco }}</label>
          <p-select
            inputId="f-preset"
            [options]="presetOptions()"
            [ngModel]="form().preset"
            (ngModelChange)="onPresetChange($event)"
            optionLabel="label"
            optionValue="value"
            appendTo="body"
          />
          <small class="muted">{{ 'expenses.form.recurrenceHint' | transloco }}</small>
        </div>

        @if (custom()) {
          <div class="field">
            <label for="f-interval">{{ 'expenses.fields.every' | transloco }}</label>
            <div class="custom-recurrence">
              <p-inputnumber
                inputId="f-interval"
                [ngModel]="form().interval"
                (ngModelChange)="patchForm({ interval: $event })"
                [min]="1"
                [max]="1000"
                [showButtons]="true"
              />
              <p-select
                [options]="periodUnitOptions()"
                [ngModel]="form().period"
                (ngModelChange)="patchForm({ period: $event })"
                optionLabel="label"
                optionValue="value"
                appendTo="body"
                [ariaLabel]="'expenses.fields.period' | transloco"
              />
            </div>
          </div>
        }

        <div class="field">
          <label for="f-start">{{ 'expenses.fields.startDate' | transloco }}</label>
          <input
            id="f-start"
            class="app-input"
            type="date"
            [ngModel]="form().startDate"
            (ngModelChange)="patchForm({ startDate: $event })"
          />
          <small class="muted">{{ 'expenses.form.startHint' | transloco }}</small>
        </div>

        <div class="field">
          <label for="f-expiry">{{ 'expenses.fields.expiryDate' | transloco }}</label>
          <input
            id="f-expiry"
            class="app-input"
            type="date"
            [ngModel]="form().expiryDate"
            (ngModelChange)="patchForm({ expiryDate: $event })"
          />
          <small class="muted">{{ 'expenses.form.expiryHint' | transloco }}</small>
        </div>

        <div class="field span-2">
          <label for="f-categories">{{ 'expenses.fields.categories' | transloco }}</label>
          <p-multiselect
            inputId="f-categories"
            [options]="categoryOptions()"
            [ngModel]="form().categoryIds"
            (ngModelChange)="patchForm({ categoryIds: $event })"
            optionLabel="label"
            optionValue="value"
            [filter]="true"
            filterBy="label"
            [fluid]="true"
            appendTo="body"
            [placeholder]="'expenses.form.pickCategories' | transloco"
          />
          @if (categoryOptions().length === 0) {
            <small class="muted">{{ 'expenses.form.noCategories' | transloco }}</small>
          }
        </div>

        @if (dateOrderWrong()) {
          <p class="form-error span-2">{{ 'expenses.form.datesWrongWay' | transloco }}</p>
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

    <p-dialog
      [visible]="chartOpen()"
      (visibleChange)="chartOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(52rem, 95vw)' }"
      [header]="'expenses.chart.title' | transloco"
    >
      @if (chartOpen()) {
        <!-- The same two signals the page is using, so the dialog and the table behind it can
             never be showing different periods or currencies. -->
        <div class="chart-controls">
          <div class="field">
            <label for="ch-period">{{ 'expenses.totals.showPer' | transloco }}</label>
            <p-select
              inputId="ch-period"
              [options]="periodOptions()"
              [ngModel]="totalsPeriod()"
              (ngModelChange)="totalsPeriod.set($event)"
              optionLabel="label"
              optionValue="value"
              appendTo="body"
            />
          </div>
          <div class="field">
            <label for="ch-valuein">{{ 'expenses.totals.showIn' | transloco }}</label>
            <p-select
              inputId="ch-valuein"
              [options]="valueInOptions()"
              [ngModel]="valueIn()"
              (ngModelChange)="valueIn.set($event)"
              optionLabel="label"
              optionValue="value"
              appendTo="body"
            />
          </div>
        </div>

        @if (breakdown().unit === null) {
          <p class="chart-blocked">{{ 'expenses.chart.pickCurrency' | transloco }}</p>
        } @else if (breakdown().slices.length === 0) {
          <p class="muted">{{ 'expenses.chart.empty' | transloco }}</p>
        } @else {
          <app-expense-pie-chart
            [slices]="breakdown().slices"
            [unit]="breakdown().unit ?? ''"
            [dark]="theme.dark()"
            [shareLabel]="'expenses.chart.shareOfTotal' | transloco"
            [otherNote]="otherNote()"
          />
          <p class="muted chart-note">
            {{
              'expenses.chart.note'
                | transloco
                  : {
                      counted: activeCount(),
                      period: periodNoun(),
                      unit: breakdown().unit,
                    }
            }}
            @if (breakdown().unpriced > 0) {
              <span class="chart-warning">
                {{ 'expenses.chart.unpriced' | transloco: { count: breakdown().unpriced } }}
              </span>
            }
          </p>
        }
      }
    </p-dialog>

    <app-csv-import-dialog
      [(visible)]="importOpen"
      [endpoint]="api"
      titleKey="import.expenseItems.title"
      hintKey="import.expenseItems.hint"
      sampleFileName="expense-items-sample.csv"
      exportBaseName="expense-items"
      (imported)="reload()"
    />
  `,
  styles: `
    .page-hint {
      margin-top: -0.5rem;
    }

    .view-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: 1.25rem;
      margin-bottom: 1rem;
    }

    .switch-field {
      flex-direction: row;
      align-items: center;
      gap: 0.6rem;
    }

    .switch-field > label {
      opacity: 1;
    }

    .totals-box {
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
      margin-inline-start: auto;
      text-align: right;
    }

    .totals-label {
      font-size: 0.8rem;
      font-weight: 600;
      opacity: 0.75;
    }

    .totals-values {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 0.75rem;
    }

    .totals-value {
      font-size: 1.15rem;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }

    .totals-note {
      font-size: 0.78rem;
    }

    .grouped-note {
      margin-top: 0;
    }

    /* A finished or not-yet-started row is shown rather than hidden, but it is not part of the
       total, so it must not read as though it were. */
    tr.finished td {
      opacity: 0.55;
    }

    .inactive-note {
      margin-inline-start: 0.5rem;
      font-size: 0.8em;
    }

    .tag-list,
    .group-totals {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem;
    }

    .group-totals {
      justify-content: flex-end;
      gap: 0.75rem;
    }

    .group-label {
      font-weight: 600;
    }

    .group-count,
    .group-total {
      font-size: 0.85em;
    }

    .group-count {
      margin-inline-start: 0.5rem;
    }

    .group-total {
      font-weight: 600;
      font-size: 1em;
    }

    .chart-controls {
      display: flex;
      flex-wrap: wrap;
      gap: 1.25rem;
      margin-bottom: 0.5rem;
    }

    .chart-note {
      margin: 0.5rem 0 0;
      font-size: 0.85em;
    }

    .chart-warning {
      color: var(--app-error-color, #991b1b);
      margin-inline-start: 0.35rem;
    }

    .chart-blocked {
      margin: 2rem 0;
      text-align: center;
    }

    /* A sum that left something out for want of a rate, shown as ">= x" with the reason on hover -
       the same marker the wealth and dividend screens use for a partial total. */
    .partial {
      text-decoration: underline dotted;
      text-underline-offset: 0.2rem;
      cursor: help;
    }

    .custom-recurrence {
      display: flex;
      gap: 0.5rem;
    }

    .custom-recurrence > p-inputnumber {
      flex: 0 0 7.5rem;
    }

    .custom-recurrence > p-select {
      flex: 1 1 auto;
    }

    .form-error {
      color: var(--app-error-color, #991b1b);
      margin: 0;
    }
  `,
})
export class ExpenseItemsPage {
  /** Protected rather than private: the import dialog takes it as an input from the template. */
  protected readonly api = inject(ExpenseItemApi);
  private readonly categoryApi = inject(ExpenseCategoryApi);
  /**
   * The wealth slice's rates, reused across the feature boundary on purpose - exactly as
   * DividendService reuses ExchangeRateService on the backend. They are the owner's own marks, not
   * wealth-specific data, and quoting them twice would let two screens disagree about what a
   * currency is worth on the same day.
   */
  private readonly rateApi = inject(ExchangeRateApi);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly transloco = inject(TranslocoService);
  /** echarts paints a canvas and inherits nothing, so the chart is told the theme explicitly. */
  protected readonly theme = inject(ThemeService);

  protected readonly loading = signal(false);
  protected readonly pageSizeOptions = PAGE_SIZE_OPTIONS;
  protected readonly saving = signal(false);
  private readonly items = signal<ExpenseItem[]>([]);
  private readonly categories = signal<ExpenseCategory[]>([]);

  protected readonly search = signal('');
  protected readonly unitFilter = signal<string | null>(null);
  protected readonly categoryFilter = signal<number | null>(null);
  protected readonly activeOnly = signal(false);

  protected readonly view = signal<ExpenseView>('list');
  protected readonly totalsPeriod = signal<RecurrencePeriod>('MONTHLY');

  /**
   * Which currency the normalised figures are shown in. AS_ENTERED - a sentinel string rather than
   * null, because p-select cannot tell a null value from nothing being selected - leaves every row
   * in its own currency and totals per currency, which is the default because it is the only
   * reading that involves no exchange rate at all.
   */
  protected readonly valueIn = signal<string>(AS_ENTERED);
  private readonly rates = signal<RateTable | null>(null);

  protected readonly dialogOpen = signal(false);
  protected readonly importOpen = signal(false);
  protected readonly chartOpen = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly form = signal<ItemForm>(emptyForm());

  /** Re-render the imperatively translated bits whenever a language is switched or loaded. */
  private readonly i18nTick = toSignal(
    merge(this.transloco.langChanges$, this.transloco.events$),
    { initialValue: null },
  );

  protected readonly custom = computed(() => this.form().preset === CUSTOM_RECURRENCE);

  /**
   * Filtering happens here rather than on the server. The page holds every row anyway - the totals
   * and the grouping both need all of them - so narrowing server-side would be a round trip per
   * keystroke that returned a subset of what is already in memory.
   */
  protected readonly filtered = computed(() => {
    const needle = this.search().trim().toLowerCase();
    const unit = this.unitFilter();
    const categoryId = this.categoryFilter();
    const activeOnly = this.activeOnly();

    return this.items().filter((item) => {
      if (needle.length > 0 && !item.name.toLowerCase().includes(needle)) {
        return false;
      }
      if (unit !== null && item.unit !== unit) {
        return false;
      }
      if (categoryId !== null && !item.categories.some((category) => category.id === categoryId)) {
        return false;
      }
      return !activeOnly || item.active;
    });
  });

  /**
   * Only active rows are totalled. "What do I pay regularly" should not include a loan that
   * finished in 2021 or a subscription that starts next year - and those rows are still listed,
   * greyed, with the footer saying how many were left out, so the difference is visible rather
   * than silently applied.
   */
  private readonly counted = computed(() => this.filtered().filter((item) => item.active));

  protected readonly activeCount = computed(() => this.counted().length);

  protected readonly converting = computed(() => this.valueIn() !== AS_ENTERED);

  protected readonly grandTotals = computed(() =>
    totalsByUnit(this.counted(), this.totalsPeriod(), this.valueIn(), this.rates()),
  );

  private readonly groups = computed<CategoryGroup[]>(() =>
    groupByCategory(this.filtered(), this.totalsPeriod(), this.valueIn(), this.rates()),
  );

  /**
   * The pie's slices, over the same active rows the totals count.
   *
   * `unit` comes back null when nothing is being converted and the rows are in more than one
   * currency: a pie is a part-to-whole picture and there is no whole yet, so the dialog says so
   * instead of drawing something that adds euros to forints.
   */
  protected readonly breakdown = computed(() => {
    this.i18nTick();
    return shareBreakdown(
      this.counted(),
      this.totalsPeriod(),
      this.valueIn(),
      this.rates(),
      this.transloco.translate('expenses.chart.other'),
    );
  });

  protected readonly otherNote = computed(() => {
    this.i18nTick();
    const folded = this.breakdown().folded;
    return folded > 0
      ? this.transloco.translate('expenses.chart.otherNote').replace('{count}', String(folded))
      : '';
  });

  /**
   * Normalised figures, computed once per (period, currency, rates) rather than per cell - the
   * grouped view renders the same expense under each of its tags, so a per-cell call would convert
   * it more than once.
   */
  private readonly normalisedById = computed(() => {
    const period = this.totalsPeriod();
    const valueIn = this.valueIn();
    const rates = this.rates();
    const values = new Map<number, number | null>();
    for (const item of this.items()) {
      values.set(item.id, perPeriodIn(item, period, valueIn, rates));
    }
    return values;
  });

  /**
   * The grouped view, as a two-level tree so it reads like the wealth tracker's sub-items: a
   * category parent, the expenses under it, and the subtotal on the parent row.
   */
  protected readonly tree = computed<TreeNode<ExpenseRow>[]>(() =>
    this.groups().map((group) => ({
      key: group.key,
      data: {
        kind: 'group' as const,
        label: group.category?.category ?? this.transloco.translate('expenses.view.uncategorised'),
        item: null,
        totals: group.totals,
        count: group.items.length,
      },
      // Expanded by default: a collapsed tree of three categories hides the very thing the view
      // was switched to in order to see.
      expanded: true,
      leaf: group.items.length === 0,
      children: group.items.map((item) => ({
        // The same expense can sit under two categories, so the item id is not unique in this
        // tree - the key has to carry the group as well.
        key: `${group.key}:${item.id}`,
        data: {
          kind: 'item' as const,
          label: item.name,
          item,
          totals: [],
          count: 0,
        },
        leaf: true,
      })),
    })),
  );

  protected readonly unitOptions = computed(() => {
    this.i18nTick();
    const units = [...new Set(this.items().map((item) => item.unit))].sort((a, b) =>
      a.localeCompare(b),
    );
    return units.map((unit) => ({ label: unit, value: unit }));
  });

  protected readonly categoryFilterOptions = computed(() =>
    this.categories().map((category) => ({ label: category.category, value: category.id })),
  );

  /**
   * "As entered", then every currency worth offering: the ones the expenses are actually in, plus
   * the ones the stored rates mention. The second half matters because the currency somebody wants
   * to compare in - HUF, usually - need not be one any single bill is denominated in.
   */
  protected readonly valueInOptions = computed(() => {
    this.i18nTick();
    const units = new Set<string>([
      ...this.items().map((item) => item.unit),
      ...unitsInRates(this.rates()),
    ]);
    return [
      { label: this.transloco.translate('expenses.totals.asEntered'), value: AS_ENTERED },
      ...[...units]
        .sort((a, b) => a.localeCompare(b))
        .map((unit) => ({ label: unit, value: unit })),
    ];
  });

  protected readonly categoryOptions = this.categoryFilterOptions;

  protected readonly viewOptions = computed(() => {
    this.i18nTick();
    return [
      { label: this.transloco.translate('expenses.view.list'), value: 'list' as ExpenseView },
      {
        label: this.transloco.translate('expenses.view.grouped'),
        value: 'categories' as ExpenseView,
      },
    ];
  });

  /** "Show totals per day / week / month / year". */
  protected readonly periodOptions = computed(() => {
    this.i18nTick();
    return RECURRENCE_PERIODS.map((period) => ({
      label: this.transloco.translate(`expenses.per.${period}`),
      value: period,
    }));
  });

  /** The plural period names the custom recurrence row uses: "weeks", "months". */
  protected readonly periodUnitOptions = computed(() => {
    this.i18nTick();
    return RECURRENCE_PERIODS.map((period) => ({
      label: this.transloco.translate(`expenses.periodUnit.${period}`),
      value: period,
    }));
  });

  /**
   * The presets, plus Custom. One dropdown covers every recurrence anybody actually has, and
   * Custom is what keeps "every 7 weeks" possible without making everyone else answer two
   * questions - the stored shape is the same pair either way.
   */
  protected readonly presetOptions = computed(() => {
    this.i18nTick();
    return [
      ...RECURRENCE_PRESETS.map((preset) => ({
        label: this.transloco.translate(`expenses.recurrence.${preset.id}`),
        value: preset.id,
      })),
      { label: this.transloco.translate('expenses.recurrence.custom'), value: CUSTOM_RECURRENCE },
    ];
  });

  protected readonly periodNoun = computed(() => {
    this.i18nTick();
    return this.transloco.translate(`expenses.per.${this.totalsPeriod()}`);
  });

  protected readonly dateOrderWrong = computed(() => {
    const value = this.form();
    return (
      value.startDate.length > 0 &&
      value.expiryDate.length > 0 &&
      value.expiryDate < value.startDate
    );
  });

  protected readonly formValid = computed(() => {
    const value = this.form();
    return (
      value.name.trim().length > 0 &&
      value.amount !== null &&
      value.amount >= 0 &&
      value.unit.trim().length > 0 &&
      value.interval >= 1 &&
      !this.dateOrderWrong()
    );
  });

  constructor() {
    this.reload();
    this.loadCategories();
    this.loadRates();
  }

  protected patchForm(patch: Partial<ItemForm>): void {
    this.form.update((current) => ({ ...current, ...patch }));
  }

  /** Units are upper-cased as they are typed, so the field shows what will be stored. */
  protected onUnitInput(value: string): void {
    this.patchForm({ unit: value.toUpperCase() });
  }

  /** Picking a preset writes the pair it stands for; picking Custom leaves the pair alone. */
  protected onPresetChange(preset: string): void {
    const match = presetById(preset);
    if (match) {
      this.patchForm({ preset, interval: match.interval, period: match.period });
    } else {
      this.patchForm({ preset: CUSTOM_RECURRENCE });
    }
  }

  /**
   * What each sortable column orders by. Several are computed rather than stored: recurrence sorts
   * by how often it comes round (every 2 weeks before monthly), the per-period column by the
   * figure on screen, and the active period by when it starts.
   */
  private readonly itemSortKeys: Record<string, (item: ExpenseItem) => unknown> = {
    name: (item) => item.name,
    amount: (item) => item.amount,
    recurrence: (item) => item.recurrenceInterval * PERIOD_DAYS[item.recurrencePeriod],
    perPeriod: (item) => this.normalised(item),
    categories: (item) => item.categories.map((category) => category.category).join(', ') || null,
    activePeriod: (item) => item.startDate ?? item.expiryDate,
  };

  protected sortItems(event: SortFunctionEvent<ExpenseItem>): void {
    if (event.data) {
      const key = this.itemSortKeys[event.field ?? ''] ?? this.itemSortKeys['name'];
      sortInPlace(event.data, key, event.order);
    }
  }

  /**
   * The grouped view sorts the expenses *within* each category and leaves the categories where
   * they are (alphabetical, "Uncategorised" last) - the grouping is the point of the view, and
   * reshuffling the groups on every click would make the rows under them harder to find.
   * PrimeNG calls this once per sibling list, so the category level simply returns untouched.
   */
  protected sortTree(event: SortFunctionEvent<TreeNode<ExpenseRow>>): void {
    const nodes = event.data;
    if (!nodes?.length || nodes[0].data?.kind !== 'item') {
      return;
    }
    const key = this.itemSortKeys[event.field ?? ''] ?? this.itemSortKeys['name'];
    sortInPlace(nodes, (node) => (node.data?.item ? key(node.data.item) : null), event.order);
  }

  /** Null when no stored rate connects this row's currency to the chosen one - never zero. */
  protected normalised(item: ExpenseItem): number | null {
    return this.normalisedById().get(item.id) ?? null;
  }

  /** The currency the normalised figure is in: the row's own, or the one being compared in. */
  protected rowUnit(item: ExpenseItem): string {
    return displayUnit(item, this.valueIn());
  }

  /** "Monthly", or "Every 7 weeks" for a recurrence no preset covers. */
  protected recurrenceLabel(item: ExpenseItem): string {
    const preset = presetIdFor(item.recurrenceInterval, item.recurrencePeriod);
    if (preset !== CUSTOM_RECURRENCE) {
      return this.transloco.translate(`expenses.recurrence.${preset}`);
    }
    return this.transloco.translate('expenses.recurrence.everyN', {
      interval: item.recurrenceInterval,
      period: this.transloco.translate(`expenses.periodUnit.${item.recurrencePeriod}`),
    });
  }

  /** Both dates are optional, and each absent one is an open end rather than a blank. */
  protected rangeLabel(item: ExpenseItem): string {
    if (item.startDate && item.expiryDate) {
      return this.transloco.translate('expenses.range.between', {
        from: item.startDate,
        to: item.expiryDate,
      });
    }
    if (item.startDate) {
      return this.transloco.translate('expenses.range.from', { from: item.startDate });
    }
    if (item.expiryDate) {
      return this.transloco.translate('expenses.range.until', { to: item.expiryDate });
    }
    return this.transloco.translate('expenses.range.open');
  }

  protected reload(): void {
    this.loading.set(true);
    this.api.listAll().subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected openCreate(): void {
    this.editingId.set(null);
    this.form.set(emptyForm());
    this.dialogOpen.set(true);
  }

  protected openEdit(item: ExpenseItem): void {
    this.editingId.set(item.id);
    this.form.set({
      name: item.name,
      amount: item.amount,
      unit: item.unit,
      // An "every 7 weeks" row opens on Custom with its real values rather than being rounded to
      // the nearest preset behind the user's back.
      preset: presetIdFor(item.recurrenceInterval, item.recurrencePeriod),
      interval: item.recurrenceInterval,
      period: item.recurrencePeriod,
      startDate: item.startDate ?? '',
      expiryDate: item.expiryDate ?? '',
      categoryIds: item.categories.map((category) => category.id),
    });
    this.dialogOpen.set(true);
  }

  protected save(): void {
    const value = this.form();
    if (value.amount === null) {
      return;
    }

    const body: ExpenseItemRequest = {
      name: value.name.trim(),
      amount: value.amount,
      unit: value.unit.trim().toUpperCase(),
      recurrenceInterval: value.interval,
      recurrencePeriod: value.period,
      startDate: value.startDate || null,
      expiryDate: value.expiryDate || null,
      categoryIds: value.categoryIds,
    };

    const id = this.editingId();
    const request$ = id === null ? this.api.create(body) : this.api.update(id, body);

    this.saving.set(true);
    request$.subscribe({
      next: () => {
        this.saving.set(false);
        this.dialogOpen.set(false);
        this.toast('expenses.items.saved');
        this.reload();
        // A CSV import can invent categories, and so can another tab; re-reading them here keeps
        // the picker honest without a second refresh button.
        this.loadCategories();
      },
      error: () => this.saving.set(false),
    });
  }

  protected confirmDelete(item: ExpenseItem): void {
    this.confirm.confirm({
      header: this.transloco.translate('expenses.items.deleteTitle'),
      message: this.transloco.translate('expenses.items.deleteMessage', { name: item.name }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.transloco.translate('common.delete'),
      rejectLabel: this.transloco.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      accept: () =>
        this.api.remove(item.id).subscribe(() => {
          this.toast('expenses.items.deleted');
          this.reload();
        }),
    });
  }

  private loadCategories(): void {
    this.categoryApi.listAll().subscribe({
      next: (categories) => this.categories.set(categories),
      error: () => this.categories.set([]),
    });
  }

  /**
   * The newest quote per pair, in one small request - not the whole rate history, which grows by a
   * row per pair per day once the central-bank feed is running. A recurring expense carries no date
   * of its own, so "the most recent rate available" is the only thing it can be converted with.
   */
  private loadRates(): void {
    this.rateApi.latest().subscribe({
      next: (quotes) => this.rates.set(buildRateTable(quotes)),
      // No rates is not an error state: everything simply stays in its own currency, and picking
      // another one leaves the rows unconvertible rather than wrong.
      error: () => this.rates.set(null),
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
