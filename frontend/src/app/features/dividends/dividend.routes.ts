import { Routes } from '@angular/router';

export const DIVIDEND_ROUTES: Routes = [
  { path: '', redirectTo: 'received', pathMatch: 'full' },
  {
    path: 'received',
    loadComponent: () => import('./dividends.page').then((m) => m.DividendsPage),
  },
  {
    path: 'monthly',
    loadComponent: () =>
      import('./dividend-monthly.page').then((m) => m.DividendMonthlyPage),
  },
];
