import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

// Mantine needs these browser APIs, which jsdom does not provide.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

// Tests never reach Firebase: every test gets this fake service, set up per test where needed.
vi.mock('@/features/auth/authService', () => ({
  authService: {
    login: vi.fn(),
    restore: vi.fn(async () => null),
    getIdToken: vi.fn(async () => null),
    logout: vi.fn(async () => {}),
    sendPasswordReset: vi.fn(async () => {}),
    changePassword: vi.fn(async () => {}),
  },
}));

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});
