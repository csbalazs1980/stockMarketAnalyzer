import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'sma.theme';
const DARK_CLASS = 'app-dark';

/** Matches the darkModeSelector passed to providePrimeNG in app.config.ts. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly dark = signal(false);

  restore(): void {
    let stored: string | null = null;
    try {
      stored = localStorage.getItem(STORAGE_KEY);
    } catch {
      stored = null;
    }

    const prefersDark =
      stored === null && window.matchMedia?.('(prefers-color-scheme: dark)').matches === true;

    this.apply(stored === 'dark' || prefersDark);
  }

  toggle(): void {
    this.apply(!this.dark());
  }

  private apply(dark: boolean): void {
    this.dark.set(dark);
    document.documentElement.classList.toggle(DARK_CLASS, dark);
    try {
      localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light');
    } catch {
      /* ignore */
    }
  }
}
