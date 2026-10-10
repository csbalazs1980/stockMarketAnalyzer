import { AppEnvironment } from './app-environment';

export const environment: AppEnvironment = {
  production: false,
  // Empty on purpose: every API URL becomes relative, so the browser sends it to whatever
  // host is serving the app — the Cloudflare tunnel hostname — and `proxy.conf.mjs` forwards
  // it to http://localhost:8080 server-side. Same origin, so no CORS; same site, so the
  // SameSite=Lax session cookies still work. See "Run with Cloudflare tunnel" in README.md.
  apiBaseUrl: '',
};
