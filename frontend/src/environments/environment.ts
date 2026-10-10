import { AppEnvironment } from './app-environment';

/**
 * Production values. `ng serve` / `ng build --configuration development` swap this
 * file for environment.development.ts (see the fileReplacements in angular.json).
 */
export const environment: AppEnvironment = {
  production: true,
  apiBaseUrl: '',
};
