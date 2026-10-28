import { Navigate, Outlet, useLocation } from 'react-router';
import { paths } from '@/app/paths';
import { FullPageLoader } from '@/shared/ui/FullPageLoader';
import { useAuth } from './AuthProvider';

// A session only exists for an active account with the ADMIN role (see FirebaseAuthService).
export function RequireAuth() {
  const { isAuthenticated, isRestoring } = useAuth();
  const location = useLocation();

  if (isRestoring) {
    return <FullPageLoader />;
  }
  if (!isAuthenticated) {
    return <Navigate to={paths.login} replace state={{ from: location }} />;
  }
  return <Outlet />;
}
