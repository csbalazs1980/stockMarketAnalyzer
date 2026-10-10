import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ConfirmationService, MessageService, TreeNode } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TreeTableModule } from 'primeng/treetable';

import { buildEntryTree, WealthEntryApi } from './wealth.api';
import {
  DEFAULT_VALUE_UNIT,
  WealthEntry,
  WealthEntryCloneRequest,
  WealthEntryQuery,
  WealthEntryRequest,
} from './wealth.models';
import { SortFunctionEvent, sortInPlace } from '../../core/table/table-sort';

interface EntryFilters {
  from: string;
  to: string;
  unit: string;
  search: string;
  valueIn: string;
}

interface EntryForm {
  name: string;
  unit: string;
  quantity: number | null;
  entryDate: string;
  parentId: number | null;
}

/**
 * One line of the clone form. `sourceUnit` is what the row would fall back to if the unit box
 * is left empty, which is how the root's unit reaches the leaves without being retyped.
 */
interface CloneRow {
  sourceId: number;
  name: string;
  depth: number;
  sourceUnit: string;
  quantity: number | null;
  unit: string;
}

/** What a clone row ends up denominated in, and what it would inherit if its box were empty. */
interface CloneUnit {
  effective: string;
  inherited: string;
}

/** Same belt and braces as the backend: a parent chain corrupted into a loop must not hang the UI. */
const MAX_CLONE_DEPTH = 32;

const EMPTY_FILTERS: EntryFilters = {
  from: '',
  to: '',
  unit: '',
  search: '',
  valueIn: DEFAULT_VALUE_UNIT,
};

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function emptyForm(parentId: number | null): EntryForm {
  return { name: '', unit: '', quantity: null, entryDate: today(), parentId };
}

@Component({
  selector: 'app-wealth-entries-page',
  imports: [
    DecimalPipe,
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    DialogModule,
    InputNumberModule,
    InputTextModule,
    SelectModule,
    TreeTableModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-header">
      <h1>{{ 'wealth.entries.title' | transloco }}</h1>
      <div class="row-actions">
        <p-button
          icon="pi pi-refresh"
          [text]="true"
          [ariaLabel]="'common.refresh' | transloco"
          (onClick)="reload()"
        />
        <p-button
          icon="pi pi-plus"
          [label]="'wealth.entries.new' | transloco"
          (onClick)="openCreate(null)"
        />
      </div>
    </div>

    <div class="filter-bar">
      <div class="field">
        <label for="f-from">{{ 'wealth.filters.from' | transloco }}</label>
        <input
          id="f-from"
          class="app-input"
          type="date"
          [ngModel]="draft().from"
          (ngModelChange)="patchDraft({ from: $event })"
        />
      </div>
      <div class="field">
        <label for="f-to">{{ 'wealth.filters.to' | transloco }}</label>
        <input
          id="f-to"
          class="app-input"
          type="date"
          [ngModel]="draft().to"
          (ngModelChange)="patchDraft({ to: $event })"
        />
      </div>
      <div class="field">
        <label for="f-unit">{{ 'wealth.filters.unit' | transloco }}</label>
        <input
          pInputText
          id="f-unit"
          [ngModel]="draft().unit"
          (ngModelChange)="patchDraft({ unit: $event })"
          placeholder="USD"
        />
      </div>
      <div class="field">
        <label for="f-search">{{ 'wealth.filters.search' | transloco }}</label>
        <input
          pInputText
          id="f-search"
          [ngModel]="draft().search"
          (ngModelChange)="patchDraft({ search: $event })"
          (keyup.enter)="apply()"
        />
      </div>
      <div class="field">
        <label for="f-valuein">{{ 'wealth.filters.valueIn' | transloco }}</label>
        <input
          pInputText
          id="f-valuein"
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

    <div class="table-scroll">
      <p-treetable
        [value]="tree()"
        [loading]="loading()"
        [customSort]="true"
        (sortFunction)="sortTree($event)"
        styleClass="p-treetable-sm"
      >
        <ng-template #header>
          <tr>
            <th ttSortableColumn="name" style="min-width: 14rem">
              {{ 'wealth.fields.name' | transloco }} <p-treeTableSortIcon field="name" />
            </th>
            <th ttSortableColumn="unit" style="min-width: 6rem">
              {{ 'wealth.fields.unit' | transloco }} <p-treeTableSortIcon field="unit" />
            </th>
            <th ttSortableColumn="quantity" class="numeric" style="min-width: 9rem">
              {{ 'wealth.fields.quantity' | transloco }} <p-treeTableSortIcon field="quantity" />
            </th>
            <th ttSortableColumn="entryDate" style="min-width: 9rem">
              {{ 'wealth.fields.entryDate' | transloco }} <p-treeTableSortIcon field="entryDate" />
            </th>
            <th ttSortableColumn="value" class="numeric" style="min-width: 11rem">
              {{ 'wealth.fields.value' | transloco }} <p-treeTableSortIcon field="value" />
            </th>
            <th style="width: 12rem"></th>
          </tr>
        </ng-template>

        <ng-template #body let-rowNode let-rowData="rowData">
          <tr>
            <td>
              <p-treeTableToggler [rowNode]="rowNode" />
              <span>{{ rowData.name }}</span>
              @if (rowNode.node.children?.length; as childCount) {
                <span
                  class="muted child-count"
                  [title]="'table.childCount' | transloco: { count: childCount }"
                  >({{ childCount }})</span
                >
              }
            </td>
            <td>{{ rowData.unit }}</td>
            <td class="numeric">
              @if (rowData.totalQuantity !== null) {
                <span [class.rolled-up]="rowData.totalQuantity !== rowData.quantity">
                  {{ rowData.totalQuantity | number: '1.0-6' }}
                </span>
              } @else if (rowData.quantity !== null) {
                {{ rowData.quantity | number: '1.0-6' }}
              } @else {
                <span class="muted" [title]="'wealth.mixedUnits' | transloco">&mdash;</span>
              }
            </td>
            <td>{{ rowData.entryDate }}</td>
            <td class="numeric">
              @if (rowData.totalValue === null) {
                <span class="muted" [title]="'wealth.noRate' | transloco">&mdash;</span>
              } @else {
                <span
                  [class.partial]="rowData.totalValuePartial"
                  [title]="rowData.totalValuePartial ? ('wealth.partialValue' | transloco) : ''"
                >
                  @if (rowData.totalValuePartial) {
                    <span aria-hidden="true">&ge;&nbsp;</span>
                  }
                  {{ rowData.totalValue | number: '1.0-2' }}
                  <span class="muted">{{ rowData.valueUnit }}</span>
                </span>
              }
            </td>
            <td>
              <div class="row-actions">
                <p-button
                  icon="pi pi-plus"
                  [text]="true"
                  [rounded]="true"
                  [ariaLabel]="'wealth.entries.addChild' | transloco"
                  (onClick)="openCreate(rowData)"
                />
                <p-button
                  icon="pi pi-pencil"
                  [text]="true"
                  [rounded]="true"
                  [ariaLabel]="'common.edit' | transloco"
                  (onClick)="openEdit(rowData)"
                />
                <p-button
                  icon="pi pi-copy"
                  [text]="true"
                  [rounded]="true"
                  [ariaLabel]="'wealth.entries.clone' | transloco"
                  (onClick)="openClone(rowData)"
                />
                <p-button
                  icon="pi pi-trash"
                  severity="danger"
                  [text]="true"
                  [rounded]="true"
                  [ariaLabel]="'common.delete' | transloco"
                  (onClick)="confirmDelete(rowData)"
                />
              </div>
            </td>
          </tr>
        </ng-template>

        <ng-template #emptymessage>
          <tr>
            <td colspan="6" class="muted">{{ 'wealth.entries.empty' | transloco }}</td>
          </tr>
        </ng-template>

        <ng-template #summary>
          <span class="muted table-count">
            {{ 'table.rowCount' | transloco: { count: entries().length } }}
          </span>
        </ng-template>
      </p-treetable>
    </div>

    <p-dialog
      [visible]="dialogOpen()"
      (visibleChange)="dialogOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(38rem, 95vw)' }"
      [header]="
        (editingId() === null ? 'wealth.entries.newTitle' : 'wealth.entries.editTitle') | transloco
      "
    >
      <div class="dialog-form">
        <div class="field span-2">
          <label for="e-name">{{ 'wealth.fields.name' | transloco }}</label>
          <input
            pInputText
            id="e-name"
            [ngModel]="form().name"
            (ngModelChange)="patchForm({ name: $event })"
            autocomplete="off"
          />
        </div>

        <div class="field">
          <label for="e-unit">{{ 'wealth.fields.unit' | transloco }}</label>
          <input
            pInputText
            id="e-unit"
            [ngModel]="form().unit"
            (ngModelChange)="onUnitInput($event)"
            placeholder="HUF"
            autocomplete="off"
          />
        </div>

        <div class="field">
          <label for="e-quantity">{{ 'wealth.fields.quantity' | transloco }}</label>
          <p-inputnumber
            inputId="e-quantity"
            [ngModel]="form().quantity"
            (ngModelChange)="patchForm({ quantity: $event })"
            [minFractionDigits]="0"
            [maxFractionDigits]="6"
          />
          <small class="muted">{{ 'wealth.form.quantityHint' | transloco }}</small>
        </div>

        <div class="field">
          <label for="e-date">{{ 'wealth.fields.entryDate' | transloco }}</label>
          <input
            id="e-date"
            class="app-input"
            type="date"
            [ngModel]="form().entryDate"
            (ngModelChange)="patchForm({ entryDate: $event })"
          />
        </div>

        <div class="field">
          <label for="e-parent">{{ 'wealth.fields.parent' | transloco }}</label>
          <p-select
            inputId="e-parent"
            [options]="parentOptions()"
            [ngModel]="form().parentId"
            (ngModelChange)="onParentChange($event)"
            optionLabel="label"
            optionValue="value"
            [showClear]="true"
            [filter]="true"
            filterBy="label"
            appendTo="body"
            [placeholder]="'wealth.form.noParent' | transloco"
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

    <p-dialog
      [visible]="cloneOpen()"
      (visibleChange)="cloneOpen.set($event)"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(44rem, 95vw)' }"
      [header]="'wealth.entries.cloneTitle' | transloco"
    >
      <div class="clone-head">
        <div class="clone-name">
          <span class="muted">{{ 'wealth.fields.name' | transloco }}:</span>
          <strong>{{ cloneSource()?.name }}</strong>
        </div>
        <div class="field">
          <label for="c-date">{{ 'wealth.fields.entryDate' | transloco }}</label>
          <input
            id="c-date"
            class="app-input"
            type="date"
            [ngModel]="cloneDate()"
            (ngModelChange)="cloneDate.set($event)"
          />
        </div>
      </div>

      <p class="muted clone-hint">{{ 'wealth.entries.cloneHint' | transloco }}</p>

      <div class="clone-rows">
        @for (row of cloneRows(); track row.sourceId; let i = $index) {
          <div class="clone-row" [style.padding-inline-start.rem]="row.depth * 1.25">
            <label class="clone-label" [for]="'c-q-' + row.sourceId" [title]="row.name">
              {{ row.name }}
            </label>
            <p-inputnumber
              [inputId]="'c-q-' + row.sourceId"
              [ngModel]="row.quantity"
              (ngModelChange)="patchCloneRow(i, { quantity: $event })"
              [minFractionDigits]="0"
              [maxFractionDigits]="6"
            />
            <input
              pInputText
              class="clone-unit"
              [ngModel]="row.unit"
              (ngModelChange)="patchCloneRow(i, { unit: $event })"
              [placeholder]="cloneUnits()[i].inherited"
              [attr.aria-label]="'wealth.fields.unit' | transloco"
              autocomplete="off"
            />
          </div>
        }
      </div>

      <ng-template #footer>
        <p-button
          [label]="'common.cancel' | transloco"
          severity="secondary"
          [text]="true"
          (onClick)="cloneOpen.set(false)"
        />
        <p-button
          [label]="'wealth.entries.cloneAccept' | transloco"
          icon="pi pi-copy"
          [loading]="cloning()"
          [disabled]="!cloneValid()"
          (onClick)="saveClone()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .filter-actions {
      flex-direction: row;
      align-items: flex-end;
      gap: 0.5rem;
    }

    /* A number that came from the subtree rather than from this row itself. */
    .rolled-up {
      opacity: 0.85;
      font-style: italic;
    }

    .partial {
      text-decoration: underline dotted;
      text-underline-offset: 0.2rem;
      cursor: help;
    }

    .clone-head {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: 1rem;
    }

    .clone-name {
      display: flex;
      align-items: baseline;
      gap: 0.4rem;
    }

    .clone-hint {
      margin: 0.75rem 0 0.25rem;
      font-size: 0.85rem;
    }

    /* Scrolls on its own so the date and the buttons stay put on a deep tree. */
    .clone-rows {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      max-height: min(50vh, 26rem);
      overflow-y: auto;
      padding-block: 0.25rem;
    }

    .clone-row {
      display: grid;
      grid-template-columns: minmax(6rem, 1fr) minmax(8rem, 12rem) 6rem;
      align-items: center;
      gap: 0.5rem;
    }

    .clone-label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .clone-unit {
      text-transform: uppercase;
    }

    @media (max-width: 32rem) {
      .clone-row {
        grid-template-columns: 1fr;
      }
    }
  `,
})
export class WealthEntriesPage {
  private readonly api = inject(WealthEntryApi);
  private readonly confirm = inject(ConfirmationService);
  private readonly messages = inject(MessageService);
  private readonly transloco = inject(TranslocoService);

  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly entries = signal<WealthEntry[]>([]);

  protected readonly draft = signal<EntryFilters>({ ...EMPTY_FILTERS });
  private readonly applied = signal<EntryFilters>({ ...EMPTY_FILTERS });

  protected readonly dialogOpen = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly form = signal<EntryForm>(emptyForm(null));

  /** Once the unit has been typed into by hand, picking a parent stops overwriting it. */
  private readonly unitTouched = signal(false);

  protected readonly cloneOpen = signal(false);
  protected readonly cloning = signal(false);
  protected readonly cloneSource = signal<WealthEntry | null>(null);
  /** One date for the whole copy: a clone is a snapshot, not a mix of dates. */
  protected readonly cloneDate = signal(today());
  protected readonly cloneRows = signal<CloneRow[]>([]);

  protected readonly tree = computed<TreeNode<WealthEntry>[]>(() =>
    buildEntryTree(this.entries()),
  );

  /**
   * Sorting reorders siblings only: PrimeNG calls this once per sibling list, top level first, so
   * every entry stays under its parent and the children of each row are ordered among themselves.
   * Quantity and value sort by the rolled-up figures the row actually shows.
   */
  protected sortTree(event: SortFunctionEvent<TreeNode<WealthEntry>>): void {
    const keys: Record<string, (entry: WealthEntry) => unknown> = {
      name: (entry) => entry.name,
      unit: (entry) => entry.unit,
      quantity: (entry) => entry.totalQuantity ?? entry.quantity,
      entryDate: (entry) => entry.entryDate,
      value: (entry) => entry.totalValue,
    };
    const key = keys[event.field ?? ''] ?? keys['name'];
    if (event.data) {
      sortInPlace(event.data, (node) => (node.data ? key(node.data) : null), event.order);
    }
  }

  /** Every entry except the one being edited and everything below it. */
  protected readonly parentOptions = computed(() => {
    const editing = this.editingId();
    const all = this.entries();
    const blocked = new Set<number>();

    if (editing !== null) {
      blocked.add(editing);
      let grew = true;
      while (grew) {
        grew = false;
        for (const entry of all) {
          if (entry.parentId !== null && blocked.has(entry.parentId) && !blocked.has(entry.id)) {
            blocked.add(entry.id);
            grew = true;
          }
        }
      }
    }

    return all
      .filter((entry) => !blocked.has(entry.id))
      .map((entry) => ({
        label: `${entry.name} (${entry.unit}, ${entry.entryDate})`,
        value: entry.id,
      }));
  });

  /**
   * Resolves each clone row's unit top-down, so an empty box shows what it will inherit rather
   * than nothing. `stack[depth]` is the effective unit of the last row seen at that depth, which
   * is the parent of everything that follows until the depth drops back.
   */
  protected readonly cloneUnits = computed<CloneUnit[]>(() => {
    const stack: string[] = [];
    return this.cloneRows().map((row) => {
      const inherited = row.depth === 0 ? row.sourceUnit : (stack[row.depth - 1] ?? row.sourceUnit);
      const typed = row.unit.trim().toUpperCase();
      const effective = typed || inherited;
      stack[row.depth] = effective;
      return { effective, inherited };
    });
  });

  protected readonly cloneValid = computed(() => {
    const rows = this.cloneRows();
    return (
      this.cloneDate().length > 0 &&
      rows.length > 0 &&
      rows.every((row) => row.quantity === null || row.quantity >= 0)
    );
  });

  /**
   * Quantity is optional: leaving it empty is how you declare an aggregate row. Only the
   * label and the unit are genuinely required.
   */
  protected readonly formValid = computed(() => {
    const value = this.form();
    return (
      value.name.trim().length > 0 &&
      value.unit.trim().length > 0 &&
      (value.quantity === null || value.quantity >= 0)
    );
  });

  constructor() {
    this.reload();
  }

  protected patchDraft(patch: Partial<EntryFilters>): void {
    this.draft.update((current) => ({ ...current, ...patch }));
  }

  protected patchForm(patch: Partial<EntryForm>): void {
    this.form.update((current) => ({ ...current, ...patch }));
  }

  protected onUnitInput(value: string): void {
    this.unitTouched.set(true);
    this.patchForm({ unit: value.toUpperCase() });
  }

  /**
   * A child is denominated in whatever its parent is, unless the unit was typed in by hand.
   * Prefill rather than enforcement, so a top-level node can still gather USD, HUF and OZ
   * subtrees - the roll-up sums value, which works across units.
   */
  protected onParentChange(parentId: number | null): void {
    this.patchForm({ parentId });

    if (this.unitTouched() || parentId === null) {
      return;
    }
    const parent = this.entries().find((entry) => entry.id === parentId);
    if (parent) {
      this.patchForm({ unit: parent.unit });
    }
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
    const query: WealthEntryQuery = {
      from: filters.from || null,
      to: filters.to || null,
      unit: filters.unit || null,
      search: filters.search || null,
      valueIn: filters.valueIn || DEFAULT_VALUE_UNIT,
      // One page is enough to build the whole tree; paging would split parents
      // from their children. Revisit if a snapshot ever grows past this.
      size: 500,
    };

    this.loading.set(true);
    this.api.list(query).subscribe({
      next: (page) => {
        this.entries.set(page.content);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected openCreate(parent: WealthEntry | null): void {
    this.editingId.set(null);
    this.unitTouched.set(false);
    this.form.set(emptyForm(parent?.id ?? null));
    if (parent) {
      this.patchForm({ unit: parent.unit, entryDate: parent.entryDate });
    }
    this.dialogOpen.set(true);
  }

  protected openEdit(entry: WealthEntry): void {
    this.editingId.set(entry.id);
    // Re-parenting an existing row must never silently redenominate it.
    this.unitTouched.set(true);
    this.form.set({
      name: entry.name,
      unit: entry.unit,
      quantity: entry.quantity,
      entryDate: entry.entryDate,
      parentId: entry.parentId,
    });
    this.dialogOpen.set(true);
  }

  /**
   * Builds the bulk form from the copy of the forest the page already holds - no extra request,
   * and the rows appear in the same order the table shows them.
   */
  protected openClone(entry: WealthEntry): void {
    const childrenByParent = new Map<number, WealthEntry[]>();
    for (const candidate of this.entries()) {
      if (candidate.parentId === null) {
        continue;
      }
      const siblings = childrenByParent.get(candidate.parentId) ?? [];
      siblings.push(candidate);
      childrenByParent.set(candidate.parentId, siblings);
    }

    const rows: CloneRow[] = [];
    const seen = new Set<number>();

    const walk = (node: WealthEntry, depth: number): void => {
      if (depth > MAX_CLONE_DEPTH || seen.has(node.id)) {
        return;
      }
      seen.add(node.id);
      rows.push({
        sourceId: node.id,
        name: node.name,
        depth,
        sourceUnit: node.unit,
        // Prefilled from the source: an unchanged holding needs no typing. null stays null,
        // so an aggregate row is still an aggregate in the copy.
        quantity: node.quantity,
        unit: node.unit,
      });
      for (const child of childrenByParent.get(node.id) ?? []) {
        walk(child, depth + 1);
      }
    };
    walk(entry, 0);

    this.cloneSource.set(entry);
    this.cloneDate.set(today());
    this.cloneRows.set(rows);
    this.cloneOpen.set(true);
  }

  protected patchCloneRow(index: number, patch: Partial<CloneRow>): void {
    this.cloneRows.update((rows) =>
      rows.map((row, position) => (position === index ? { ...row, ...patch } : row)),
    );
  }

  protected saveClone(): void {
    const source = this.cloneSource();
    if (source === null) {
      return;
    }

    const body: WealthEntryCloneRequest = {
      entryDate: this.cloneDate() || null,
      overrides: this.cloneRows().map((row) => ({
        sourceId: row.sourceId,
        // Sent as typed, null included: that is how a row is recorded as an aggregate.
        quantity: row.quantity,
        // An empty box is deliberate - the backend fills it from the row above.
        unit: row.unit.trim() ? row.unit.trim().toUpperCase() : null,
      })),
    };

    this.cloning.set(true);
    this.api.cloneSubtree(source.id, body).subscribe({
      next: (created) => {
        this.cloning.set(false);
        this.cloneOpen.set(false);
        this.toast('wealth.entries.cloned', { count: created.length });
        this.reload();
      },
      error: () => this.cloning.set(false),
    });
  }

  protected save(): void {
    const value = this.form();

    const body: WealthEntryRequest = {
      name: value.name.trim(),
      unit: value.unit.trim().toUpperCase(),
      // null is meaningful: it marks this row as a pure aggregate.
      quantity: value.quantity,
      entryDate: value.entryDate || null,
      parentId: value.parentId,
    };

    const id = this.editingId();
    const request$ = id === null ? this.api.create(body) : this.api.update(id, body);

    this.saving.set(true);
    request$.subscribe({
      next: () => {
        this.saving.set(false);
        this.dialogOpen.set(false);
        this.toast('wealth.entries.saved');
        this.reload();
      },
      error: () => this.saving.set(false),
    });
  }

  protected confirmDelete(entry: WealthEntry): void {
    this.confirm.confirm({
      header: this.transloco.translate('wealth.entries.deleteTitle'),
      message: this.transloco.translate('wealth.entries.deleteMessage', { name: entry.name }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.transloco.translate('common.delete'),
      rejectLabel: this.transloco.translate('common.cancel'),
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => this.remove(entry, false),
    });
  }

  private remove(entry: WealthEntry, cascade: boolean): void {
    this.api.remove(entry.id, cascade).subscribe({
      next: () => {
        this.toast('wealth.entries.deleted');
        this.reload();
      },
      error: (error: unknown) => {
        // 409 is the backend refusing to take a subtree down silently.
        if (error instanceof HttpErrorResponse && error.status === 409 && !cascade) {
          this.confirm.confirm({
            header: this.transloco.translate('wealth.entries.cascadeTitle'),
            message: this.transloco.translate('wealth.entries.cascadeMessage', {
              name: entry.name,
            }),
            icon: 'pi pi-exclamation-triangle',
            acceptLabel: this.transloco.translate('wealth.entries.cascadeAccept'),
            rejectLabel: this.transloco.translate('common.cancel'),
            acceptButtonStyleClass: 'p-button-danger',
            accept: () => this.remove(entry, true),
          });
        }
      },
    });
  }

  private toast(key: string, params?: Record<string, unknown>): void {
    this.messages.add({
      severity: 'success',
      summary: this.transloco.translate(key, params),
      life: 2500,
    });
  }
}
