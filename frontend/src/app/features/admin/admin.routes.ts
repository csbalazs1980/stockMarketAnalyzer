import { Routes } from '@angular/router';

export const ADMIN_ROUTES: Routes = [
  { path: '', redirectTo: 'users', pathMatch: 'full' },
  {
    path: 'users',
    loadComponent: () => import('./users.page').then((m) => m.UsersPage),
  },
  {
    path: 'instruments',
    loadComponent: () => import('./instruments.page').then((m) => m.InstrumentsPage),
  },
  {
    path: 'inflation',
    loadComponent: () => import('./inflation-rates.page').then((m) => m.InflationRatesPage),
  },
];
