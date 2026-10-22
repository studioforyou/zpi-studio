import { initializeApp } from 'firebase/app';
import { getAuth } from 'firebase/auth';
import { env } from '@/shared/config/env';

// Keeps the user signed in across reloads and browser restarts (local persistence, the web
// default), like the mobile app.
export const firebaseAuth = getAuth(initializeApp(env.firebase));
