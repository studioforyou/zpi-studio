export type AppFlavor = 'dev' | 'staging';

export const env = {
  // The Vite mode selects the environment and its .env.<mode>.local file.
  appFlavor: (import.meta.env.MODE === 'staging' ? 'staging' : 'dev') as AppFlavor,
};
