import type { ReactNode } from 'react';
import { cn } from 'cn';
import { Skeleton } from '@/components/ui/skeleton';
import { OrSeparator } from './auth-form';

/* Same size as the real forms, so nothing moves when they arrive. */

function Text({
  children,
  className,
}: {
  children: ReactNode;
  className: string;
}) {
  return (
    <Skeleton
      className={cn('w-fit max-w-full text-transparent select-none', className)}
    >
      {children}
    </Skeleton>
  );
}

function Field({
  label,
  description,
  ruleIcon = false,
}: {
  label: string;
  description?: string;
  /** The label shares its line with the icon of the password rule. */
  ruleIcon?: boolean;
}) {
  return (
    <div className="flex w-full flex-col gap-2">
      <div className={cn('flex items-center', ruleIcon && 'h-6')}>
        <Text className="text-sm leading-snug font-medium">{label}</Text>
      </div>
      <Skeleton className="h-11 w-full" />
      {description ? (
        <Text className="text-sm leading-normal">{description}</Text>
      ) : null}
    </div>
  );
}

function RememberMeSkeleton() {
  return (
    <div className="flex min-h-11 w-full items-center gap-2">
      <Skeleton className="size-5 shrink-0" />
      <Text className="text-sm leading-snug">Rester connecté</Text>
    </div>
  );
}

function GoogleSkeleton() {
  return <Skeleton className="min-h-11 w-full" />;
}

/** Hidden from assistive technologies: the screen announces the loading through `LoadingStatus`. */
function Loading({ children }: { children: ReactNode }) {
  return (
    <div aria-busy="true" className="flex flex-col gap-6">
      <div aria-hidden="true" className="flex flex-col gap-6">
        {children}
      </div>
    </div>
  );
}

/** Mirrors `AuthForm` and `AuthFields`: the fields, then the main button. */
function Form({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col gap-6">
      <div className="flex w-full flex-col gap-4">{children}</div>
      <Skeleton className="min-h-11 w-full" />
    </div>
  );
}

/** Mirrors `AuthLinks`. */
function Links({ labels }: { labels: string[] }) {
  return (
    <div className="mt-2 flex flex-col items-center gap-6 text-sm">
      {labels.map((label) => (
        <Text key={label} className="text-sm">
          {label}
        </Text>
      ))}
    </div>
  );
}

export function LoginFormSkeleton() {
  return (
    <Loading>
      <Form>
        <Field label="Email ou pseudo" />
        <Field label="Mot de passe" />
        <RememberMeSkeleton />
      </Form>
      <OrSeparator />
      <GoogleSkeleton />
      <Links
        labels={['Mot de passe oublié ?', 'Pas encore de compte ? S’inscrire']}
      />
    </Loading>
  );
}

export function SignupFormSkeleton() {
  return (
    <Loading>
      <Form>
        <Field label="Email" />
        <Field label="Mot de passe" ruleIcon />
        <Field label="Confirmer le mot de passe" />
        <div className="flex w-full items-start gap-2">
          <Skeleton className="mt-0.5 size-5 shrink-0" />
          <Text className="text-sm leading-snug">
            J’accepte les conditions d’utilisation et la politique de
            confidentialité.
          </Text>
        </div>
        <RememberMeSkeleton />
      </Form>
      <OrSeparator />
      <GoogleSkeleton />
      <Links labels={['Déjà un compte ? Se connecter']} />
    </Loading>
  );
}

export function RegistrationCodeFormSkeleton() {
  return (
    <Loading>
      <div className="flex flex-col items-center gap-2">
        <div className="flex gap-1.5 min-[360px]:gap-2">
          {[0, 1, 2, 3, 4, 5].map((index) => (
            <Skeleton
              key={index}
              className="size-10 min-[360px]:size-12 sm:size-13"
            />
          ))}
        </div>
        <div className="min-h-10" />
      </div>
      <Skeleton className="min-h-11 w-full" />
    </Loading>
  );
}

export function UsernameFormSkeleton() {
  return (
    <Loading>
      <Form>
        <Field label="Pseudo" />
      </Form>
    </Loading>
  );
}
