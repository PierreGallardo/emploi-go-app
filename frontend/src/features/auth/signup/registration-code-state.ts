import { maxRetryAfterSeconds } from '../errors';
import { resendSchema } from '../schemas';

/** What the code screen keeps from the form that asked for the code, in the navigation state so a reload keeps it; the API keeps the registration itself in the session. */
export type RegistrationCodeState = {
  email: string;
  /** The box « Rester connecté » of the form, which « Modifier » gives back. */
  rememberMe: boolean;
  /** The identifier of the registration of this page, sent back with the code. */
  registration: string;
  /** Instant (ms since epoch) before which the address gets no other email. */
  resendAt?: number;
};

export function readRegistrationCodeState(
  state: unknown,
): RegistrationCodeState | null {
  if (typeof state !== 'object' || state === null) return null;
  const { email, rememberMe, registration, resendAt } = state as Record<
    string,
    unknown
  >;
  if (
    typeof email !== 'string' ||
    email !== email.trim() ||
    !resendSchema.shape.email.safeParse(email).success ||
    typeof registration !== 'string' ||
    !registration
  )
    return null;
  return {
    email,
    rememberMe: rememberMe === true,
    registration,
    // A restored state may hold any number: a wait beyond the cap of the API would hold the resend for good.
    ...(typeof resendAt === 'number' &&
    resendAt <= Date.now() + maxRetryAfterSeconds * 1000
      ? { resendAt }
      : {}),
  };
}

/** What « Modifier » gives back to the signup form: never the password. */
export type SignupFormState = { email: string; rememberMe: boolean };

export function readSignupFormState(state: unknown): SignupFormState | null {
  if (typeof state !== 'object' || state === null) return null;
  const { email, rememberMe } = state as Record<string, unknown>;
  return typeof email === 'string'
    ? { email, rememberMe: rememberMe === true }
    : null;
}
