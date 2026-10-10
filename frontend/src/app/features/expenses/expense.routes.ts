import { Routes } from '@angular/router';

export const EXPENSE_ROUTES: Routes = [
  { path: '', redirectTo: 'items', pathMatch: 'full' },
  {
    path: 'items',
    loadComponent: () => import('./expense-items.page').then((m) => m.ExpenseItemsPage),
  },
  {
    // Categories are their own screen rather than a dialog on the items page: they are shared
    // between items, an administrator manages global ones here, and both are importable.
    path: 'categories',
    loadComponent: () =>
      import('./expense-categories.page').then((m) => m.ExpenseCategoriesPage),
  },
];
