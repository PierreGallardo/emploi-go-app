import {
  Links,
  Meta,
  Outlet,
  Scripts,
  ScrollRestoration,
  isRouteErrorResponse,
} from 'react-router';
import type { ReactNode } from 'react';
import { z } from 'zod';
import type { Route } from './+types/root';
import { AppProviders } from './providers';
import { Button } from './components/ui/button';
import { AuthLayout } from './components/auth-layout';
import { PageTitle } from './components/page-title';
import { EmploiGoLogo } from './components/emploi-go-logo';
// A small file would otherwise be inlined as a data: URL, which script-src 'self' refuses.
import themeInit from './lib/theme-init.js?url&no-inline';
import './app.css';

// The CSP forbids eval, which Zod would otherwise try once to compile its object schemas.
z.config({ jitless: true });

export function Layout({ children }: { children: ReactNode }) {
  return (
    <html lang="fr">
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="referrer" content="no-referrer" />
        {/* Neither async nor a module: the stored theme applies before the first paint. */}
        <script src={themeInit} />
        <Meta />
        <Links />
      </head>
      <body>
        {children}
        <ScrollRestoration />
        <Scripts />
      </body>
    </html>
  );
}

export default function App() {
  return (
    <AppProviders>
      <Outlet />
    </AppProviders>
  );
}

/** The page of every route that is not prerendered, until the JavaScript takes over. */
export function HydrateFallback() {
  return (
    <main
      id="main-content"
      className="flex min-h-svh flex-col items-center justify-center gap-4 px-4"
    >
      <PageTitle>Chargement</PageTitle>
      <EmploiGoLogo className="h-16 w-auto text-foreground" />
      <h1 className="text-sm text-muted-foreground">Chargement…</h1>
    </main>
  );
}

export function ErrorBoundary({ error }: Route.ErrorBoundaryProps) {
  const missing = isRouteErrorResponse(error) && error.status === 404;
  const heading = missing ? 'Page introuvable' : 'Une erreur est survenue';
  return (
    <AuthLayout>
      <PageTitle>{heading}</PageTitle>
      <div className="flex flex-col gap-6 text-center">
        <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">
          {heading}
        </h1>
        <Button asChild className="min-h-11 w-full">
          <a href="/">Revenir à l’accueil</a>
        </Button>
      </div>
    </AuthLayout>
  );
}
