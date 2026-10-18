export type AppFlavor = 'dev' | 'staging';

export const env = {
  // The Vite mode selects the environment and its .env.<mode>.local file.
  appFlavor: (import.meta.env.MODE === 'staging' ? 'staging' : 'dev') as AppFlavor,
  apiBaseUrl: (import.meta.env.VITE_API_BASE_URL ?? '').trim().replace(/\/+$/, ''),
};
