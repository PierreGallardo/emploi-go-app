import { z } from 'zod';
import { passwordIssue } from './password-rule';
import { usernamePattern, usernameRule } from './username-rule';

export const credentialsSchema = z.object({
  identifier: z
    .string()
    .trim()
    .min(1, 'Indique ton email ou ton pseudo.')
    .max(320, 'Identifiant trop long.'),
  password: z
    .string()
    .min(1, 'Indique ton mot de passe.')
    .max(128, 'Mot de passe trop long.'),
});
export type CredentialsValues = z.infer<typeof credentialsSchema>;
export const loginSchema = credentialsSchema.extend({
  rememberMe: z.boolean(),
});
export type LoginValues = z.infer<typeof loginSchema>;

export const resendSchema = z.object({
  email: z
    .string()
    .trim()
    .email('Indique une adresse email valide.')
    .max(320, 'Adresse email trop longue.'),
});
const acceptedTermsSchema = z
  .boolean()
  .refine(
    (accepted) => accepted,
    'Accepte les conditions d’utilisation pour créer ton compte.',
  );
export const signupSchema = resendSchema
  .extend({
    password: z.string().check((context) => {
      const issue = passwordIssue(context.value);
      if (issue)
        context.issues.push({
          code: 'custom',
          message: issue,
          // The confirmation is checked against the password whatever the password is worth.
          continue: true,
          input: context.value,
        });
    }),
    confirmation: z
      .string()
      .min(1, 'Confirme ton mot de passe.')
      .max(128, 'La confirmation est trop longue.'),
    acceptedTerms: acceptedTermsSchema,
    rememberMe: z.boolean(),
  })
  .refine((values) => values.password === values.confirmation, {
    path: ['confirmation'],
    message: 'Les mots de passe doivent être identiques.',
  });
export const usernameSchema = z.object({
  username: z
    .string()
    .trim()
    .min(1, { message: 'Choisis un pseudo.', abort: true })
    .regex(usernamePattern, usernameRule),
});
export const googleSignupSchema = usernameSchema.extend({
  acceptedTerms: acceptedTermsSchema,
});
export type GoogleSignupValues = z.input<typeof googleSignupSchema>;
export type SignupValues = z.input<typeof signupSchema>;
export type SignupOutput = z.output<typeof signupSchema>;
export const resetPasswordSchema = z
  .object({
    password: signupSchema.shape.password,
    confirmation: signupSchema.shape.confirmation,
  })
  .refine((values) => values.password === values.confirmation, {
    path: ['confirmation'],
    message: 'Les mots de passe doivent être identiques.',
  });
export type ResetPasswordValues = z.infer<typeof resetPasswordSchema>;
export const registrationCodeSchema = z.object({
  code: z.string().regex(/^\d{6}$/, 'Saisis les 6 chiffres du code reçu.'),
});
export type RegistrationCodeValues = z.infer<typeof registrationCodeSchema>;
