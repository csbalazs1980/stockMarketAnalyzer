import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { MenuItem } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { MenuModule } from 'primeng/menu';
import { MenubarModule } from 'primeng/menubar';
import { SelectModule } from 'primeng/select';
import { map, merge } from 'rxjs';

import { AuthService } from '../core/auth/auth.service';
import { AppLanguage, LanguageService } from '../core/i18n/language.service';
import { ThemeService } from '../core/theme/theme.service';

@Component({
  selector: 'app-top-bar',
  imports: [FormsModule, ButtonModule, MenuModule, MenubarModule, SelectModule, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p-menubar [model]="menuItems()" breakpoint="768px" styleClass="app-menubar">
      <ng-template #start>
        <span class="brand">
          <i class="pi pi-chart-line"></i>
          <span class="brand-name">{{ 'app.title' | transloco }}</span>
        </span>
      </ng-template>

      <ng-template #end>
        <div class="top-bar-actions">
          <p-select
            [options]="languageOptions"
            [ngModel]="language.active()"
            (ngModelChange)="onLanguageChange($event)"
            optionLabel="label"
            optionValue="value"
            [ariaLabel]="'settings.language' | transloco"
            styleClass="lang-select"
          />

          <p-button
            [icon]="theme.dark() ? 'pi pi-sun' : 'pi pi-moon'"
            [text]="true"
            [rounded]="true"
            [ariaLabel]="'settings.toggleTheme' | transloco"
            (onClick)="theme.toggle()"
          />

          @if (user.authenticated()) {
            <p-button
              icon="pi pi-user"
              [text]="true"
              [rounded]="!wide()"
              [label]="wide() ? user.displayName() : ''"
              [ariaLabel]="user.displayName()"
              (onClick)="userMenu.toggle($event)"
            />
            <p-menu #userMenu [model]="userMenuItems()" [popup]="true" [appendTo]="'body'" />
          } @else {
            <p-button
              icon="pi pi-sign-in"
              [text]="true"
              [rounded]="!wide()"
              [label]="wide() ? ('auth.signIn' | transloco) : ''"
              [ariaLabel]="'auth.signIn' | transloco"
              (onClick)="user.goToLogin()"
            />
          }
        </div>
      </ng-template>
    </p-menubar>
  `,
  styles: `
    :host {
      display: block;
      position: sticky;
      top: 0;
      z-index: 100;
    }

    .brand {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      font-weight: 700;
      margin-right: 1rem;
      white-space: nowrap;
    }

    .brand-name {
      display: none;
    }

    @media (min-width: 480px) {
      .brand-name {
        display: inline;
      }
    }

    .top-bar-actions {
      display: flex;
      align-items: center;
      gap: 0.25rem;
    }

    :host ::ng-deep .lang-select {
      min-width: 6.5rem;
    }
  `,
})
export class TopBar {
  protected readonly user = inject(AuthService);
  protected readonly theme = inject(ThemeService);
  protected readonly language = inject(LanguageService);

  private readonly transloco = inject(TranslocoService);
  private readonly breakpoints = inject(BreakpointObserver);

  /** Re-evaluate imperative labels whenever a language is switched or loaded. */
  private readonly i18nTick = toSignal(
    merge(this.transloco.langChanges$, this.transloco.events$),
    { initialValue: null },
  );

  protected readonly wide = toSignal(
    this.breakpoints.observe('(min-width: 768px)').pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  protected readonly languageOptions = [
    { label: 'EN', value: 'en' as AppLanguage },
    { label: 'HU', value: 'hu' as AppLanguage },
    // Serbian comes in both scripts, each labelled in its own script.
    { label: 'SR Lat', value: 'sr-Latn' as AppLanguage },
    { label: 'СР Ћир', value: 'sr-Cyrl' as AppLanguage },
    { label: 'RU', value: 'ru' as AppLanguage },
    { label: 'JA', value: 'ja' as AppLanguage },
  ];

  /**
   * Rebuilt whenever the language or the session changes - `isAdmin()` is a signal, so signing
   * in as an administrator makes the Administration item appear without a reload, which is the
   * same reason `AuthService` is the single source of truth rather than a bootstrap snapshot.
   */
  protected readonly menuItems = computed<MenuItem[]>(() => {
    this.i18nTick();
    const t = (key: string) => this.transloco.translate(key);

    const items: MenuItem[] = [
      { label: t('nav.home'), icon: 'pi pi-home', routerLink: '/' },
      {
        label: t('nav.wealth'),
        icon: 'pi pi-wallet',
        items: [
          { label: t('nav.entries'), icon: 'pi pi-list', routerLink: '/wealth/entries' },
          { label: t('nav.rates'), icon: 'pi pi-percentage', routerLink: '/wealth/exchange-rates' },
        ],
      },
      {
        label: t('nav.dividends'),
        icon: 'pi pi-money-bill',
        items: [
          { label: t('nav.received'), icon: 'pi pi-list', routerLink: '/dividends/received' },
          { label: t('nav.monthly'), icon: 'pi pi-table', routerLink: '/dividends/monthly' },
        ],
      },
      {
        label: t('nav.expenses'),
        icon: 'pi pi-receipt',
        items: [
          { label: t('nav.expenseItems'), icon: 'pi pi-list', routerLink: '/expenses/items' },
          {
            label: t('nav.expenseCategories'),
            icon: 'pi pi-tags',
            routerLink: '/expenses/categories',
          },
        ],
      },
    ];

    // Hidden rather than disabled for anyone without the role: the API refuses these routes
    // regardless, so showing the item to a USER would only advertise a door that does not open.
    if (this.user.isAdmin()) {
      items.push({
        label: t('nav.administration'),
        icon: 'pi pi-cog',
        items: [
          { label: t('nav.users'), icon: 'pi pi-users', routerLink: '/admin/users' },
          { label: t('nav.instruments'), icon: 'pi pi-chart-line', routerLink: '/admin/instruments' },
          { label: t('nav.inflation'), icon: 'pi pi-percentage', routerLink: '/admin/inflation' },
        ],
      });
    }

    return items;
  });

  protected readonly userMenuItems = computed<MenuItem[]>(() => {
    this.i18nTick();
    return [
      {
        label: this.transloco.translate('auth.signOut'),
        icon: 'pi pi-sign-out',
        command: () => this.user.signOut(),
      },
    ];
  });

  protected onLanguageChange(lang: AppLanguage): void {
    this.language.use(lang);
  }
}
