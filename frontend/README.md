This is a [Next.js](https://nextjs.org) project bootstrapped with [`create-next-app`](https://nextjs.org/docs/app/api-reference/cli/create-next-app).

## Getting Started

Browser authentication uses same-origin `/api` server routes. The account JWT is
stored only in a host-only, `Secure`, `HttpOnly`, `SameSite=Lax` cookie; API responses
to the browser omit it. Plain HTTP cookies are permitted only on loopback hosts
for local development. Existing localStorage sessions must sign in again.

Mutations require an exact matching Origin and a double-submit CSRF token. Use
`apiFetch` for browser API requests so the CSRF header is included. Logout clears
the server cookie. Local storage contains display hints, never an account JWT.
Order sockets use separate 60-second, order-scoped tickets that cannot authenticate
account APIs. Kitchen IP-only APIs remain direct requests to the backend.

Set server-only `BACKEND_ORIGIN` to the backend origin (it defaults to the existing
`NEXT_PUBLIC_BACKEND_ORIGIN`). On Vercel, the server routes forward the platform's
sanitized client-IP header. Other deployments may set `TRUSTED_CLIENT_IP_HEADER`
only to a header overwritten by their trusted ingress; the backend must trust that
ingress through its existing `ABUSE_TRUSTED_PROXIES` setting. Without a trusted
header, limits fail safely against the frontend server's IP. Arbitrary browser
Authorization and forwarding headers are never relayed.

Pages render dynamically for a fresh CSP nonce on each response. Production scripts
require that nonce and `strict-dynamic`; inline scripts and eval are disallowed.
Inline styles remain allowed for existing React/framer-motion styles. API responses
and session pages are not cached.

First, run the development server:

```bash
npm run dev
# or
yarn dev
# or
pnpm dev
# or
bun dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.

You can start editing the page by modifying `app/page.tsx`. The page auto-updates as you edit the file.

This project uses [`next/font`](https://nextjs.org/docs/app/building-your-application/optimizing/fonts) to automatically optimize and load [Geist](https://vercel.com/font), a new font family for Vercel.

## Learn More

To learn more about Next.js, take a look at the following resources:

- [Next.js Documentation](https://nextjs.org/docs) - learn about Next.js features and API.
- [Learn Next.js](https://nextjs.org/learn) - an interactive Next.js tutorial.

You can check out [the Next.js GitHub repository](https://github.com/vercel/next.js) - your feedback and contributions are welcome!

## Deploy on Vercel

The easiest way to deploy your Next.js app is to use the [Vercel Platform](https://vercel.com/new?utm_medium=default-template&filter=next.js&utm_source=create-next-app&utm_campaign=create-next-app-readme) from the creators of Next.js.

Check out our [Next.js deployment documentation](https://nextjs.org/docs/app/building-your-application/deploying) for more details.
