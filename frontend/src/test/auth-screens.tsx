import { screen } from '@testing-library/react';
import { useEffect } from 'react';
import {
  Route,
  Routes,
  useNavigate,
  type NavigateFunction,
} from 'react-router';
import { AuthenticatedScreen } from '@/features/auth/account/components/authenticated-screen';
import { ForgotPasswordScreen } from '@/features/auth/password-reset/components/forgot-password-screen';
import { GoogleConfirmationScreen } from '@/features/auth/google/components/google-confirmation-screen';
import { GoogleScreen } from '@/features/auth/google/components/google-screen';
import { LoginScreen } from '@/features/auth/login/components/login-screen';
import { RegistrationCodeScreen } from '@/features/auth/signup/components/registration-code-screen';
import { ResetPasswordScreen } from '@/features/auth/password-reset/components/reset-password-screen';
import { SignupScreen } from '@/features/auth/signup/components/signup-screen';
import { UsernameScreen } from '@/features/auth/signup/components/username-screen';
import { testRegistration } from './handlers/auth';

let current: NavigateFunction | undefined;
function CaptureNavigation() {
  const navigate = useNavigate();
  useEffect(() => {
    current = navigate;
    return () => {
      current = undefined;
    };
  });
  return null;
}

export function navigateFromOutside(): NavigateFunction {
  if (!current) throw new Error('Aucun écran n’est rendu.');
  return current;
}

export const authScreens = (
  <>
    <CaptureNavigation />
    <Routes>
      <Route path="/connexion" element={<LoginScreen />} />
      <Route path="/inscription" element={<SignupScreen />} />
      <Route path="/inscription/code" element={<RegistrationCodeScreen />} />
      <Route path="/inscription/pseudo" element={<UsernameScreen />} />
      <Route path="/mot-de-passe-oublie" element={<ForgotPasswordScreen />} />
      <Route
        path="/reinitialiser-mot-de-passe"
        element={<ResetPasswordScreen />}
      />
      <Route path="/connexion-google" element={<GoogleScreen />} />
      <Route
        path="/confirmation-google"
        element={<GoogleConfirmationScreen />}
      />
      <Route
        path="/"
        element={
          <AuthenticatedScreen title="Carte">
            <h1>Carte</h1>
          </AuthenticatedScreen>
        }
      />
    </Routes>
  </>
);

export function codeScreenRoute(email = 'jeune@exemple.fr') {
  return {
    pathname: '/inscription/code',
    state: { email, registration: testRegistration },
  };
}

export const termsLabel =
  'J’accepte les conditions d’utilisation et la politique de confidentialité.';
export const termsCheckbox = () =>
  screen.getByRole('checkbox', { name: termsLabel });
