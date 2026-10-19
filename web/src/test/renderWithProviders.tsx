import type { ReactNode } from 'react';
import { MantineProvider } from '@mantine/core';
import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
// Not 'react-router/dom': under Vitest it loads a second copy of the router, so its context
// would not reach the hooks imported from 'react-router'.
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';
import { createQueryClient } from '@/app/queryClient';
import { theme } from '@/app/theme';
import { AuthProvider } from '@/features/auth/AuthProvider';

type RouterOptions = {
  routes: RouteObject[];
  initialEntry: string;
};

/** Renders `ui`, or the given routes in a memory router, inside the app's providers. */
export function renderWithProviders(ui: ReactNode, router?: RouterOptions) {
  const content = router ? (
    <RouterProvider
      router={createMemoryRouter(router.routes, { initialEntries: [router.initialEntry] })}
    />
  ) : (
    ui
  );
  return render(
    <MantineProvider theme={theme}>
      <QueryClientProvider client={createQueryClient()}>
        <AuthProvider>{content}</AuthProvider>
      </QueryClientProvider>
    </MantineProvider>,
  );
}
