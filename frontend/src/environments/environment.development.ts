import { AppEnvironment } from './app-environment';

export const environment: AppEnvironment = {
  production: false,
  // Absolute, so the browser really does a cross-origin call and exercises the
  // backend's CORS config (app.cors.allowed-origin-patterns already allows
  // http://localhost:*). Change to '' plus a dev-server proxy if you prefer.
  apiBaseUrl: 'http://localhost:8080',
};
