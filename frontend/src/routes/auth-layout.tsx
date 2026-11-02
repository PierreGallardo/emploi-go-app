import { Outlet } from 'react-router';
import { AuthLayout } from '@/components/auth-layout';

export default function Authentication() {
  return (
    <AuthLayout>
      <Outlet />
    </AuthLayout>
  );
}
