import { Routes } from '@angular/router';

export const WEALTH_ROUTES: Routes = [
  { path: '', redirectTo: 'entries', pathMatch: 'full' },
  {
    path: 'entries',
    loadComponent: () =>
      import('./wealth-entries.page').then((m) => m.WealthEntriesPage),
  },
  {
    path: 'exchange-rates',
    loadComponent: () =>
      import('./exchange-rates.page').then((m) => m.ExchangeRatesPage),
  },
];
