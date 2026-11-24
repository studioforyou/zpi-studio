# Client app redesign

Accepted design of the client mobile app (issue #281) and the documents needed before implementation.

| Document | What it holds |
| --- | --- |
| [prototype.html](prototype.html) | Clickable prototype of the whole client app in light and dark theme. Open it in a browser; the side panel switches personas and lists where each flow is. |
| [components.md](components.md) | Tokens, typography and components for Flutter. |
| [backend-check.md](backend-check.md) | Which API each new flow uses and what is missing. |
| [rollout-plan.md](rollout-plan.md) | Order of implementation, one pull request per step. |

Decisions recorded with the design (24.11.2026):

- Tabs: Karnety, Zajęcia, Wiadomości, Profil. The app opens on Zajęcia, and notifications open from the bell in every tab header.
- One calendar, Zajęcia, replaces Oferta and Mój grafik, with Moje / Wszystkie.
- Training history is a separate screen opened from the history icon in the Zajęcia header.
- Child passes are shown through a person picker above the pass picker, which defaults to the profile owner.
- Secondary buttons have a 1 px ink frame, an ink label and no fill.
- Elements without a user story are not implemented:
  - "Zawieś" on a pass;
  - "Trenerzy prowadzący" in Profil;
  - "Wyświetl odwołane zajęcia";
  - the used / reserved split of the entry legend.

The prototype's interface text is Polish, as in the app.
