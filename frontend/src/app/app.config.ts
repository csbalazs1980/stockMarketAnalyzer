import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';
import { provideEchartsCore } from 'ngx-echarts';
import { ConfirmationService, MessageService } from 'primeng/api';
import { providePrimeNG } from 'primeng/config';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
import { errorInterceptor } from './core/http/error.interceptor';
import { AVAILABLE_LANGUAGES } from './core/i18n/language.service';
import { TranslocoHttpLoader } from './core/i18n/transloco-http.loader';

/**
 * Aura's light scheme is white on white. The user asked for a genuinely grey one: 25% ink where
 * the theme had white, 40% where it had its faintest grey.
 *
 * Only the light `surface` ramp is redefined and the rest follows from it - content, overlay and
 * form-field backgrounds are `{surface.0}`, hover and row striping `{surface.100}`, borders
 * `{surface.200}`, body text `{surface.700}`. Which is also why the dark end of the ramp moves:
 * on a #bfbfbf ground Aura's slate-700 body text sits at 3.6:1 against the striped rows, under
 * the 4.5:1 AA bar, so the text end is pushed down until it clears. Dark mode is untouched.
 */
const GreyAura = definePreset(Aura, {
  semantic: {
    colorScheme: {
      light: {
        surface: {
          0: '#bfbfbf',
          50: '#ababab',
          100: '#999999',
          200: '#7a7a7a',
          300: '#666666',
          400: '#545454',
          500: '#454545',
          600: '#333333',
          700: '#262626',
          800: '#171717',
          900: '#0d0d0d',
          950: '#050505',
        },
      },
    },
  },
});

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),

    // Order matters, and this order is the reverse of the obvious one. errorInterceptor is
    // OUTERMOST so that it sees the outcome of authInterceptor's refresh-and-retry rather than
    // the 401 that triggered it: an expired access token renews silently, with no toast.
    provideHttpClient(withInterceptors([errorInterceptor, authInterceptor])),

    // Restores the session before the router runs its first guard. Without this the guards
    // would have to be asynchronous and the top bar would flash "Sign in" on every reload.
    provideAppInitializer(() => inject(AuthService).restore()),

    providePrimeNG({
      theme: {
        preset: GreyAura,
        options: {
          // ThemeService toggles this class on <html>.
          darkModeSelector: '.app-dark',
        },
      },
    }),
    MessageService,
    ConfirmationService,

    provideTransloco({
      config: {
        availableLangs: [...AVAILABLE_LANGUAGES],
        defaultLang: 'en',
        fallbackLang: 'en',
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
      },
      loader: TranslocoHttpLoader,
    }),

    // Charts are not used yet; this makes `<div echarts [options]="...">` work
    // the moment the first one is added, with echarts lazily loaded.
    provideEchartsCore({ echarts: () => import('echarts') }),
  ],
};
