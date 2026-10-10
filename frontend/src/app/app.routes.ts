import { Routes } from '@angular/router';

import { adminGuard } from './core/auth/admin.guard';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/home/home.page').then((m) => m.HomePage),
  },
  {
    // The application's own sign-in screen. Anonymous by definition, so no guard.
    path: 'login',
    loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'wealth',
    canActivate: [authGuard],
    loadChildren: () => import('./features/wealth/wealth.routes').then((m) => m.WEALTH_ROUTES),
  },
  {
    path: 'dividends',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/dividends/dividend.routes').then((m) => m.DIVIDEND_ROUTES),
  },
  {
    // Recurring expenses. authGuard, not adminGuard, even though administrators can do one extra
    // thing here (create global categories) - the screens are for everybody, and the API refuses
    // that one action on its own.
    path: 'expenses',
    canActivate: [authGuard],
    loadChildren: () => import('./features/expenses/expense.routes').then((m) => m.EXPENSE_ROUTES),
  },
  {
    // adminGuard answers the not-signed-in case itself, sending the visitor to the login page
    // exactly as authGuard would - so listing both here would only ask the same question twice.
    path: 'admin',
    canActivate: [adminGuard],
    loadChildren: () => import('./features/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
  },
  { path: '**', redirectTo: '' },
];
