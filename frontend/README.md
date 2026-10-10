# Stock Market Analyzer — frontend

Angular SPA for the Spring Boot backend in `../backend`. Built so far: the wealth
tracker (entries + exchange rates), the received-dividends table, a top menu,
HU/EN language switching and a dark mode toggle.

## Stack

| Concern | Choice | Why |
|---|---|---|
| Framework | Angular 21 (LTS until 2027-06), standalone + **zoneless** | v21 is zoneless by default; signals cover the state this app has, so no NgRx |
| UI kit | **PrimeNG 21.1.9** + `@primeuix/themes` (Aura preset) | Last MIT release. PrimeNG **22 switched to PrimeUI licensing** and needs a key — staying on 21 keeps this project key-free. `TreeTable` is why PrimeNG was picked at all: `wealth_entry` is a self-referencing tree |
| Icons | primeicons 7 (`pi pi-*`) | Same MIT-era pin |
| Charts | `ngx-echarts` 21 + Apache ECharts 6 | Wired up in `app.config.ts` but not used yet — canvas rendering for large OHLC series, and treemap/sunburst for the wealth hierarchy later |
| Auth | `keycloak-angular` 21 + `keycloak-js` 26 | Authorization code + PKCE against the `stockmarket` realm |
| i18n | `@jsverse/transloco` 8 | Runtime language switching, no reload, one build |
| Styling | Global SCSS + CSS grid; `@angular/cdk` `BreakpointObserver` for structural changes | CSS handles reflow; the CDK handles "render something different" |

## Prerequisites

- Node **^22.22.3 || ^24.15 || >=26** (Angular 21 CLI engine range)
- The backend running on `http://localhost:8080`
- Keycloak running on `http://localhost:8180` with the `stockmarket-web` client (below)

## Run
The frontend will provide on http://localhost:4200

```bash
npm install
npm start
```

`npm run build` produces a production bundle in `dist/frontend`.

> Nothing here has been compiled yet — this workspace was authored in an
> environment with no npm registry access, the same caveat the backend carries.
> `npm install && npm start` on your machine is the real smoke test.

## Run with Cloudflare tunnel

Puts the dev server on a public HTTPS URL without router port forwarding or a
public IP — `cloudflared` dials **out** from this machine, so it works behind
CGNAT. Requires [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/install-and-setup/installation/)
(`winget install --id Cloudflare.cloudflared`).

**Fixed tunnel name: `stockmarket-frontend`.** Used by every command below.

### 1. Quick tunnel — no account, random URL

Works immediately, but Cloudflare hands out a **new hostname every run**, so the
tunnel name is local bookkeeping only:

`.trycloudflare.com` is already listed in `angular.json` → `serve.options.allowedHosts`
(leading dot = that domain and every subdomain), so no flags are needed:

```bash
npm run start:tunnel
# in a second terminal:
cloudflared tunnel --url http://localhost:4200
# expected URL: https://<random-words>.trycloudflare.com   ← printed by cloudflared, changes each run
```

**Use `start:tunnel`, not `start`** — see *Why the API goes through a proxy* below.

### 2. Named tunnel — stable URL, needs a domain on Cloudflare

`cloudflared tunnel route dns` writes a DNS record, so it only works once a
domain's nameservers point at Cloudflare. Until then, use the quick tunnel above.

```bash
cloudflared tunnel login                                              # once, per machine
cloudflared tunnel create stockmarket-frontend                        # once — writes <UUID>.json
cloudflared tunnel route dns stockmarket-frontend sma.example.com     # once — creates the CNAME
cloudflared tunnel run stockmarket-frontend                           # every time
# expected URL: https://sma.example.com
```

`%USERPROFILE%\.cloudflared\config.yml` — with this in place, `cloudflared tunnel
run stockmarket-frontend` needs no flags:

```yaml
tunnel: stockmarket-frontend
credentials-file: C:\Users\<you>\.cloudflared\<TUNNEL-UUID>.json

ingress:
  # expected URL: https://sma.example.com  → this machine's ng serve on 4200
  - hostname: sma.example.com
    service: http://localhost:4200
  # every other hostname that reaches this tunnel
  - service: http_status:404
```

Then add the hostname to `angular.json` → `serve.options.allowedHosts` alongside
`.trycloudflare.com`, and run `npm run start:tunnel` as usual.

Replace `sma.example.com` with your own hostname throughout — `example.com` is a
placeholder, not a working domain.

### Why `allowedHosts`, and why it lives in `angular.json`

- The Vite-backed dev server rejects requests whose `Host` header it does not
  recognise, which is every tunnel hostname; without it the tunnel resolves and
  then serves `Invalid Host header`.
- **It cannot be passed on the command line.** The dev-server schema declares
  `allowedHosts` as `oneOf: [array, boolean]`, which the CLI's schema-to-yargs
  bridge cannot turn into a value-taking flag — it registers a boolean, so
  `ng serve --allowed-hosts .trycloudflare.com` fails with
  `Argument: project, Given: ".trycloudflare.com"`. Edit `angular.json` instead.
- **`--host 0.0.0.0` is *not* needed.** `cloudflared` runs on this machine and
  connects to `localhost:4200` itself; binding the dev server to every interface
  only widens exposure on the LAN.

### Why the API goes through a proxy

`environment.development.ts` points `apiBaseUrl` at `http://localhost:8080`
absolutely — deliberately, so ordinary `npm start` exercises the real CORS path.
Through a tunnel that same absolute URL breaks twice over:

- **CORS.** The page is on `https://<something>.trycloudflare.com`, so the browser
  sends that as `Origin`. `app.cors.allowed-origin-patterns` lists only
  `http://localhost:*` and `http://127.0.0.1:*`, so no `Access-Control-Allow-Origin`
  comes back and the login POST dies in the browser before `AuthApi` sees a response.
- **Cookies.** `sma_at` / `sma_rt` are `SameSite=Lax` with `secure: false`.
  `trycloudflare.com` → `localhost` is cross-*site*, so even with CORS widened the
  session cookies would not be stored and every later call would come back 401.

And for anyone other than you, `localhost:8080` is *their* machine, not yours.

The `tunnel` configuration fixes all three at once. `environment.tunnel.ts` sets
`apiBaseUrl: ''` so every API URL is relative; `proxy.conf.mjs` forwards `/api` to
`http://localhost:8080` server-side. The browser only ever talks to the tunnel
hostname — same origin, so no CORS; same site, so `SameSite=Lax` is satisfied — and
no backend configuration changes at all.

```
browser ──https──▶ tunnel hostname ──▶ ng serve :4200 ──/api──▶ localhost:8080
          (one origin, one site)          (proxy.conf.mjs)
```

`proxy.conf.mjs` strips the `Origin` header on the way through: http-proxy forwards
it verbatim, and Spring Security treats "Origin present and different from Host" as
a CORS request, which would then be refused by the same patterns as before.

**The alternative**, if you would rather the SPA call the API directly: tunnel the
backend under a second hostname, point `apiBaseUrl` at it, add that frontend origin
to `app.cors.allowed-origin-patterns`, set `AUTH_COOKIE_SECURE=true` and
`AUTH_COOKIE_SAME_SITE=None` — and customise the CSRF repository, since `csrf.spa()`'s
own cookie is not covered by that property. More moving parts for the same result.

> **This is a dev stack.** The compose setup ships seed users `sma-admin`/`admin`
> and `sma-user`/`user`, a Keycloak console admin `admin`/`admin`, and the client
> secret `dev-only-secret-change-me`. A public URL gets scanned within minutes —
> change those before leaving a tunnel running, or put Cloudflare Access in front
> of the hostname.

## Keycloak: the `stockmarket-web` client

`../backend/docker/keycloak/realm-stockmarket.json` now declares a third client:

- public, authorization code + PKCE (`S256`)
- redirect URIs `http://localhost:4200/*`, web origins `http://localhost:4200`
- post-logout redirect `http://localhost:4200/*`
- **an `oidc-audience-mapper` adding `stockmarket-api`** — without it every token
  the SPA gets is rejected by `spring.security.oauth2.resourceserver.jwt.audiences`

**Realm import only runs against an empty Keycloak database.** On an existing
stack, either add the client by hand in the admin console, or drop and re-import:

```bash
cd ../backend
docker compose -f docker.yaml stop keycloak
docker compose -f docker.yaml exec postgres psql -U stockmarket -d stockmarket -c "DROP DATABASE keycloak;"
docker compose -f docker.yaml up -d keycloak-db-init keycloak
```

## CORS

No backend change was needed: `app.cors.allowed-origin-patterns` in
`application.yaml` already covers `http://localhost:*`, which includes 4200. The
dev build therefore calls `http://localhost:8080` directly (see
`src/environments/environment.development.ts`) and exercises the real CORS path.
If you would rather avoid cross-origin calls in dev, set `apiBaseUrl: ''` and add
an `ng serve` proxy.

## Layout

```
src/app/
  core/
    auth/      keycloak providers, route guard, CurrentUser
    http/      RFC 9457 ProblemDetail types, error-toast interceptor, PageResponse
    i18n/      Transloco HTTP loader, LanguageService
    theme/     dark mode toggle (.app-dark on <html>)
  shell/       top-bar (menubar, language switch, theme, user menu)
  features/
    home/
    wealth/    models, API services, tree builder, entries + rates pages
    dividends/ models, API services, received-dividends table
public/i18n/   en.json, hu.json
```

## Notes and known gaps

- **Entries are fetched as one page of 500** and turned into a tree client-side;
  server paging would split parents from children. Revisit if a snapshot grows
  past that.
- **`currentValue: null` renders as “—”**, deliberately distinct from `0` — it
  means no rate connects the units on that date, not that the holding is worthless.
- **Deleting a parent** first tries a plain delete; the backend's `409` is caught
  and turned into a "delete the whole subtree?" prompt (the error interceptor
  passes 409 through untouched so there is no duplicate toast).
- **Editing a rate is an upsert** — POST replaces the quote for the same pair and date.
- **The dividends table pages server-side** (unlike entries, which has to build a
  tree) and asks `/dividends/summary` for the footer total separately, so the
  figure covers the whole filter rather than the visible page.
- **The dividend currency is prefilled from the instrument but stays editable** —
  a broker converting a US payout into HUF before it lands is normal, not an anomaly.
- `InstrumentApi` lives under `features/dividends/` because nothing else consumes
  it yet; move it up when the instrument screens land.
- Numbers format with the default `en-US` locale. Registering `hu` locale data
  and switching `LOCALE_ID` is a follow-up.
- No tests are wired up yet. Angular 21 defaults to Vitest; add it when the first
  test lands.
- A typed client generated from `/v3/api-docs/v1` (`openapi-generator`,
  `typescript-angular`) should eventually replace the hand-written
  `wealth.models.ts` and `dividend.models.ts`.
