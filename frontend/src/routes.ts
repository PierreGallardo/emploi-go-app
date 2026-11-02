import {
  index,
  layout,
  route,
  type RouteConfig,
} from '@react-router/dev/routes';

export default [
  index('routes/home.tsx'),
  layout('routes/auth-layout.tsx', [
    route('connexion', 'routes/login.tsx'),
    route('inscription', 'routes/signup.tsx'),
    route('inscription/code', 'routes/registration-code.tsx'),
    route('inscription/pseudo', 'routes/username.tsx'),
    route('mot-de-passe-oublie', 'routes/forgot-password.tsx'),
    route('reinitialiser-mot-de-passe', 'routes/reset-password.tsx'),
    route('connexion-google', 'routes/google.tsx'),
    route('confirmation-google', 'routes/google-confirmation.tsx'),
  ]),
] satisfies RouteConfig;
