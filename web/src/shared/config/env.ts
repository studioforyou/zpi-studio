export type AppFlavor = 'dev' | 'staging';

export const env = {
  // The Vite mode selects the environment and its .env.<mode> and .env.<mode>.local files.
  appFlavor: (import.meta.env.MODE === 'staging' ? 'staging' : 'dev') as AppFlavor,
  // Each environment has its own Firebase project, the one its backend accepts tokens from.
  firebase: {
    apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
    authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
    projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
    appId: import.meta.env.VITE_FIREBASE_APP_ID,
  },
};
