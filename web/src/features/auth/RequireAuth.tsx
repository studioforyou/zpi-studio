import { Navigate, Outlet, useLocation } from 'react-router';
import { paths } from '@/app/paths';
import { useAuth } from './AuthProvider';

// TODO(auth): also require the ADMIN role once real roles exist.
export function RequireAuth() {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to={paths.login} replace state={{ from: location }} />;
  }
  return <Outlet />;
}
