import {
  AuthenticationCoordinationError,
  AuthenticationError,
  authenticationMessage,
} from './errors';
import { usernameRule } from './username-rule';

export function googleMessage(error: unknown): string {
  if (error instanceof AuthenticationError)
    return googleCodeMessage(error.code, error);
  // The lock, not Google, failed: say what happened and how to continue.
  if (error instanceof AuthenticationCoordinationError) return error.message;
  return 'Google est temporairement indisponible. Réessaie ou utilise ton mot de passe.';
}
export function googleCodeMessage(code?: string, error?: unknown): string {
  switch (code) {
    case 'CANCELLED':
      return 'La connexion Google a été annulée. Tu peux réessayer.';
    case 'INVALID_FLOW':
    case 'EXPIRED_FLOW':
      return 'Cette demande a expiré ou a changé. Recommence avec Google.';
    case 'PROVIDER_REJECTED':
      return 'Google n’a pas pu vérifier la connexion. Recommence ou utilise ton mot de passe.';
    case 'ACCOUNT_ACCESS_REQUIRED':
      return 'Connecte-toi à ton compte Emploi Go pour y associer Google.';
    case 'EMAIL_CONFIRMATION_REQUIRED':
      return 'Confirme d’abord ton email, puis reprends Google.';
    case 'ALREADY_LINKED':
      return 'Cette association n’est plus disponible. Recommence avec la méthode habituelle du compte.';
    case 'INVALID_USERNAME':
      return usernameRule;
    case 'USERNAME_UNAVAILABLE':
      return 'Ce pseudo est déjà pris.';
    case 'INVALID_LINK':
      return 'Ce lien est invalide. Reviens à Google pour demander un nouveau lien.';
    case 'EXPIRED_LINK':
      return 'Ce lien a expiré. Reviens à Google pour demander un nouveau lien.';
    case 'USED_LINK':
      return 'Ce lien a déjà été utilisé. Continue avec Google.';
    case 'REPLACED_LINK':
      return 'Ce lien a été remplacé. Utilise le dernier lien reçu.';
    // Only the return from Google carries a bare code; a failed request has its own message.
    case 'RATE_LIMITED':
      return error
        ? authenticationMessage(error)
        : 'Trop de tentatives avec Google. Réessaie dans quelques minutes ou utilise ton mot de passe.';
    case 'UNAVAILABLE':
      return error
        ? authenticationMessage(error)
        : 'Le service est momentanément indisponible. Réessaie dans quelques instants ou utilise ton mot de passe.';
    default:
      return error
        ? authenticationMessage(error)
        : 'Google est temporairement indisponible. Réessaie ou utilise ton mot de passe.';
  }
}
