import { inject, Injectable, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

/**
 * BCP 47 tags, which are also the translation file names in public/i18n. Serbian is offered in
 * both of its scripts: sr-Latn and sr-Cyrl share one source of wording (sr-Cyrl.json is
 * transliterated from the Latin text), so they cannot drift apart in meaning.
 */
export const AVAILABLE_LANGUAGES = ['en', 'hu', 'sr-Latn', 'sr-Cyrl', 'ru', 'ja'] as const;
export type AppLanguage = (typeof AVAILABLE_LANGUAGES)[number];

const STORAGE_KEY = 'sma.lang';

function isSupported(value: string | null): value is AppLanguage {
  return value !== null && (AVAILABLE_LANGUAGES as readonly string[]).includes(value);
}

/**
 * The first browser preference this app can serve. Serbian needs care: browsers report `sr`,
 * `sr-RS`, `sr-Latn-RS` or `sr-Cyrl-RS`. An explicit script wins; without one, Cyrillic is the
 * official script and the CLDR default for `sr`, so that is what plain `sr` gets.
 */
function fromBrowser(preferences: readonly string[]): AppLanguage | null {
  for (const raw of preferences) {
    const tag = (raw ?? '').toLowerCase();
    const primary = tag.split('-')[0];
    if (primary === 'sr') {
      return tag.includes('latn') ? 'sr-Latn' : 'sr-Cyrl';
    }
    if (isSupported(primary)) {
      return primary;
    }
  }
  return null;
}

@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);

  readonly available = AVAILABLE_LANGUAGES;
  readonly active = signal<AppLanguage>('en');

  /** Called once from the root component, before anything renders text. */
  restore(): void {
    let stored: string | null = null;
    try {
      stored = localStorage.getItem(STORAGE_KEY);
    } catch {
      stored = null;
    }

    const browser = fromBrowser(navigator.languages ?? [navigator.language]);
    const initial: AppLanguage = isSupported(stored) ? stored : (browser ?? 'en');

    this.use(initial);
  }

  use(lang: AppLanguage): void {
    this.transloco.setActiveLang(lang);
    this.active.set(lang);
    document.documentElement.lang = lang;
    try {
      localStorage.setItem(STORAGE_KEY, lang);
    } catch {
      /* private mode - the choice just will not survive a reload */
    }
  }
}
