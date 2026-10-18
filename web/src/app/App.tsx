import { MantineProvider } from '@mantine/core';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router/dom';
import { AuthProvider } from '@/features/auth/AuthProvider';
import { createQueryClient } from './queryClient';
import { createAppRouter } from './router';
import { theme } from './theme';

const queryClient = createQueryClient();
const router = createAppRouter();

export function App() {
  return (
    <MantineProvider theme={theme} defaultColorScheme="light">
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </QueryClientProvider>
    </MantineProvider>
  );
}
