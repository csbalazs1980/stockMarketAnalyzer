/**
 * Dev-server proxy, used only by the `tunnel` configuration (`npm run start:tunnel`).
 *
 * Why this exists: served through a Cloudflare tunnel the SPA lives on
 * `https://<something>.trycloudflare.com`, while `apiBaseUrl: 'http://localhost:8080'`
 * points the browser at the visitor's *own* machine. Even on the developer's own
 * machine that is a cross-origin call the backend rejects — `app.cors.allowed-origin-patterns`
 * lists only `http://localhost:*` and `http://127.0.0.1:*` — and the session cookies
 * (`SameSite=Lax`, `secure: false`) would not survive the cross-site hop even if CORS passed.
 *
 * Routing `/api` through the dev server collapses both problems: the browser only ever
 * talks to the tunnel hostname, so the request is same-origin and the cookies are same-site.
 * `environment.tunnel.ts` sets `apiBaseUrl: ''` to make the SPA emit relative URLs.
 */
export default {
  '/api': {
    target: 'http://localhost:8080',
    // Rewrite the Host header to the target. Without it Spring sees the tunnel hostname.
    changeOrigin: true,
    // The target is plain HTTP; nothing to verify.
    secure: false,
    configure: (proxy) => {
      proxy.on('proxyReq', (proxyReq) => {
        // Strip the browser's Origin header. http-proxy forwards it verbatim, and Spring
        // Security treats "Origin present and different from Host" as a CORS request — which
        // would then be checked against allowed-origin-patterns and refused. Removing it makes
        // the hop look like what it actually is: a server-to-server call on localhost.
        proxyReq.removeHeader('origin');
      });
    },
  },
};
