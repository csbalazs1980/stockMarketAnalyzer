import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ToastModule } from 'primeng/toast';

import { LanguageService } from './core/i18n/language.service';
import { ThemeService } from './core/theme/theme.service';
import { TopBar } from './shell/top-bar';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, TopBar, ToastModule, ConfirmDialogModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="app-shell">
      <app-top-bar />
      <main class="app-content">
        <router-outlet />
      </main>
    </div>

    <p-toast position="bottom-right" />
    <p-confirmdialog />
  `,
})
export class App {
  constructor() {
    inject(LanguageService).restore();
    inject(ThemeService).restore();
  }
}
