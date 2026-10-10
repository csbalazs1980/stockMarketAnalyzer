import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  model,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { ToggleSwitchModule } from 'primeng/toggleswitch';

import {
  CsvImportEndpoint,
  CsvImportResult,
  isoToday,
  looksLikeCsv,
  saveBlob,
} from '../core/csv/csv-import';

/**
 * The "add a lot of rows at once" dialog -- and the way back out -- shared by every screen that
 * can import.
 *
 * <p>It knows nothing about rates, dividends or expenses: the page passes in a
 * {@link CsvImportEndpoint}, two translation keys and the name to save the sample under, and the
 * dialog handles the rest.
 *
 * <p>The file picker is a hidden native input driven by a p-button rather than PrimeNG's
 * p-fileupload. p-fileupload wants to own the request - its own progress bar, its own XHR, its
 * own idea of what a response is - and all of that would have to be fought back to route the
 * upload through HttpClient, where the bearer-token and error interceptors live.
 *
 * <p><strong>Download my data</strong> sits next to the sample rather than on the page's toolbar,
 * because the two answer the same question: the sample says what the format is, and the export is
 * that format with the user's own rows already in it. It is the whole of what they own, not what
 * the filters behind the dialog happen to be showing -- the obvious use for the file is a backup,
 * and a backup that silently left rows out would be worse than none. The file is stamped with
 * today's date, because the second one downloaded would otherwise be "dividends (1).csv".
 *
 * <p>The result stays on screen after a successful upload instead of the dialog closing itself.
 * "132 of 136 rows processed" is the answer the user came for, and a dialog that vanishes at the
 * moment it has something to say would take it with it.
 *
 * <p><strong>The optional switch</strong> (added with the expense slice) is one question about the
 * whole file, sent as an extra multipart field. Expense categories use it for "store these as
 * global categories", which is an administrator-only answer: asking once, on a screen that only
 * shows the switch to an administrator, avoids a per-row `global` column whose value from an
 * ordinary user could only be either punished or honoured - and honouring it would be a privilege
 * escalation. A page that has nothing to ask passes no keys and gets the dialog it had before.
 */
@Component({
  selector: 'app-csv-import-dialog',
  imports: [FormsModule, TranslocoPipe, ButtonModule, DialogModule, ToggleSwitchModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (onShow)="reset()"
      [modal]="true"
      [draggable]="false"
      [dismissableMask]="true"
      [style]="{ width: 'min(34rem, 95vw)' }"
      [header]="titleKey() | transloco"
    >
      <p class="muted import-hint">{{ hintKey() | transloco }}</p>

      <div class="import-downloads">
        <p-button
          icon="pi pi-download"
          [label]="'import.downloadSample' | transloco"
          severity="secondary"
          [text]="true"
          [loading]="downloading()"
          (onClick)="downloadSample()"
        />
        <p-button
          icon="pi pi-file-export"
          [label]="exportLabelKey() | transloco"
          severity="secondary"
          [text]="true"
          [loading]="exporting()"
          (onClick)="exportData()"
        />
      </div>
      <p class="muted import-downloads-hint">{{ exportHintKey() | transloco }}</p>

      <div class="import-picker">
        <p-button
          icon="pi pi-folder-open"
          [label]="'import.chooseFile' | transloco"
          severity="secondary"
          [outlined]="true"
          (onClick)="openPicker()"
        />
        <span class="import-filename" [class.muted]="!file()">
          {{ file()?.name ?? ('import.noFile' | transloco) }}
        </span>
      </div>

      <input
        #picker
        type="file"
        accept=".csv,text/csv"
        hidden
        (change)="onFileChosen($event)"
      />

      @if (toggleLabelKey(); as labelKey) {
        <div class="import-switch">
          <p-toggleswitch
            inputId="import-toggle"
            [ngModel]="toggle()"
            (ngModelChange)="toggle.set($event)"
          />
          <label for="import-toggle">{{ labelKey | transloco }}</label>
        </div>
        @if (toggleHintKey(); as hint) {
          <p class="muted import-switch-hint">{{ hint | transloco }}</p>
        }
      }

      @if (invalid()) {
        <p class="import-error">{{ 'import.notCsv' | transloco }}</p>
      }

      @if (result(); as outcome) {
        <div class="import-result">
          <p class="import-headline">
            {{
              'import.result'
                | transloco: { processed: outcome.processedRows, total: outcome.totalRows }
            }}
          </p>
          @if (outcome.duplicateRows > 0) {
            <p class="muted">
              {{ 'import.duplicates' | transloco: { count: outcome.duplicateRows } }}
            </p>
          }
          @if (outcome.failedRows > 0) {
            <p class="import-error">
              {{ 'import.failed' | transloco: { count: outcome.failedRows } }}
            </p>
          }
        </div>
      }

      <ng-template #footer>
        <p-button
          [label]="'common.close' | transloco"
          severity="secondary"
          [text]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          [label]="'import.upload' | transloco"
          icon="pi pi-upload"
          [loading]="uploading()"
          [disabled]="!file()"
          (onClick)="upload()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .import-hint {
      margin-top: 0;
    }

    .import-downloads {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.25rem;
    }

    .import-downloads-hint {
      margin: 0.35rem 0 0;
      font-size: 0.85em;
    }

    .import-picker {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      margin-top: 1rem;
    }

    .import-filename {
      overflow-wrap: anywhere;
    }

    .import-switch {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      margin-top: 1.25rem;
    }

    .import-switch label {
      font-size: 0.9rem;
      font-weight: 600;
    }

    .import-switch-hint {
      margin: 0.4rem 0 0;
      font-size: 0.85em;
    }

    .import-error {
      color: var(--app-error-color, #991b1b);
      margin-bottom: 0;
    }

    .import-result {
      margin-top: 1.25rem;
      padding-top: 1rem;
      border-top: 1px solid var(--p-content-border-color, #7a7a7a);
    }

    .import-result p {
      margin: 0 0 0.35rem;
    }

    .import-headline {
      font-weight: 600;
    }
  `,
})
export class CsvImportDialog {
  readonly visible = model.required<boolean>();

  /** The feature's API service. ExchangeRateApi, DividendApi and both expense APIs implement it. */
  readonly endpoint = input.required<CsvImportEndpoint>();
  readonly titleKey = input.required<string>();
  readonly hintKey = input.required<string>();

  /** What the sample is saved as, e.g. exchange-rates-sample.csv. */
  readonly sampleFileName = input.required<string>();

  /**
   * What an export is saved as, before the date and the extension: 'exchange-rates' becomes
   * exchange-rates-2026-09-13.csv. Required, so no screen can offer a button that saves a file
   * nobody can tell apart from the last one.
   */
  readonly exportBaseName = input.required<string>();

  /**
   * Wording of the export button and the line under it. The defaults speak of "your data", which
   * is right for every screen whose rows belong to the caller; instrument administration exports
   * shared reference data instead and says so.
   */
  readonly exportLabelKey = input<string>('import.downloadData');
  readonly exportHintKey = input<string>('import.downloadDataHint');

  /**
   * Name of the multipart field the switch is sent as, e.g. 'global'. The switch is rendered only
   * when {@link toggleLabelKey} is given, and sent only when this is too, so a page can show
   * neither, or both, but never a switch that goes nowhere.
   */
  readonly toggleField = input<string | null>(null);
  readonly toggleLabelKey = input<string | null>(null);
  readonly toggleHintKey = input<string | null>(null);

  /** Fired once the server has answered, so the page behind can refresh its table. */
  readonly imported = output<CsvImportResult>();

  private readonly picker = viewChild.required<ElementRef<HTMLInputElement>>('picker');

  protected readonly file = signal<File | null>(null);
  protected readonly invalid = signal(false);
  protected readonly uploading = signal(false);
  protected readonly downloading = signal(false);
  protected readonly exporting = signal(false);
  protected readonly result = signal<CsvImportResult | null>(null);
  protected readonly toggle = signal(false);

  protected reset(): void {
    this.file.set(null);
    this.invalid.set(false);
    this.result.set(null);
    // Off every time the dialog opens. A switch that grants everybody a shared tag must not be
    // left on from the last upload.
    this.toggle.set(false);
  }

  protected openPicker(): void {
    this.picker().nativeElement.click();
  }

  /**
   * The extension check is a courtesy, not the validation: the server checks the same thing and
   * several more it is the only one that can. Doing it here just saves a round trip on the
   * obvious mistake of picking the xlsx sitting next to the csv.
   */
  protected onFileChosen(event: Event): void {
    const input = event.target as HTMLInputElement;
    const chosen = input.files?.[0] ?? null;
    // Clearing the input means picking the same file twice still fires a change event, which is
    // what someone does after fixing the file and coming back.
    input.value = '';

    this.result.set(null);
    if (chosen && !looksLikeCsv(chosen)) {
      this.file.set(null);
      this.invalid.set(true);
      return;
    }
    this.invalid.set(false);
    this.file.set(chosen);
  }

  protected upload(): void {
    const chosen = this.file();
    if (!chosen) {
      return;
    }
    const field = this.toggleField();
    const extras = field ? { [field]: String(this.toggle()) } : undefined;

    this.uploading.set(true);
    this.endpoint()
      .importCsv(chosen, extras)
      .subscribe({
        next: (outcome) => {
          this.uploading.set(false);
          this.result.set(outcome);
          this.imported.emit(outcome);
        },
        // A rejected file is already a toast from the global error interceptor, carrying the
        // backend's own explanation of what is wrong with it.
        error: () => this.uploading.set(false),
      });
  }

  /**
   * The export is not affected by the upload, so the result of one is deliberately left on screen
   * while the other runs: somebody who has just imported a file and then downloads a backup should
   * still be able to read what the import did.
   */
  protected exportData(): void {
    this.exporting.set(true);
    this.endpoint()
      .exportCsv()
      .subscribe({
        next: (blob) => {
          this.exporting.set(false);
          saveBlob(blob, `${this.exportBaseName()}-${isoToday()}.csv`);
        },
        error: () => this.exporting.set(false),
      });
  }

  protected downloadSample(): void {
    this.downloading.set(true);
    this.endpoint()
      .downloadSample()
      .subscribe({
        next: (blob) => {
          this.downloading.set(false);
          saveBlob(blob, this.sampleFileName());
        },
        error: () => this.downloading.set(false),
      });
  }
}
