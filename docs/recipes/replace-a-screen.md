# Recipe: replace a screen

**Goal:** custom UI for one screen while the library keeps owning the rest.
**Worked, compiling example:** `template-app/.../CustomHomeScreen.kt` — copy
that pattern.

## What is (and isn't) replaceable

`MbPulseNavHost` (`mbpulse/ui/MbPulseEntryPoints.kt`) exposes four slots:

| Slot | Default screen | Callback surface |
|---|---|---|
| `studySelectionScreen` | study list | `onStudySelected(studyId)`, `onNavigateToRecovery()` |
| `measurementsScreen` | home / cards | `onCardClick(schema,title)`, `onNavigateToCollection()`, `onReset()`, `CollectionViewModel` |
| `collectionScreen` | recording UI | `CollectionViewModel`, `onBack()` |
| `historyScreen` | chart + list | `schemaName`, `title`, `onBack()`, `HistoryViewModel` |

**Adding** a screen instead of replacing one (questionnaire, diary, pairing):
`MbPulseNavHost(hostScreens = listOf(HostScreen(...)))` — listed as a card on
the home screen, opened from custom screens with
`LocalMbPulseNavigator.current.openHostScreen(id)`. See
[add-participant-input.md](add-participant-input.md).

A custom `studySelectionScreen` must join studies with a join code through
`StudySelectionViewModel.joinStudy(study, code) { onStudySelected(it) }`: it
trades the code for the join ticket the backend requires at enrolment. Calling
`onStudySelected` directly works only for studies without a code.

**Loading, onboarding (consent + passphrase) and recovery are deliberately NOT
replaceable.** They are the security- and ethics-critical path (consent
wording, key generation, recovery credentials); they stay library-owned so a
host app cannot accidentally weaken them. Do not try to fork them. The same
goes for the re-consent review and the "study ended" screen, which cover
every enrolled screen.

## Steps

1. In the host app, write the composable with the slot's exact parameter list
   (start from `CustomHomeScreen.kt`).
2. Reuse library state instead of rebuilding it:
   - The slot hands you the activity-scoped `CollectionViewModel` (live
     recording status, start/stop).
   - `hiltViewModel(activity)` gives the shared `LoadingViewModel`
     (study/consent/`Ready` state); plain `hiltViewModel()` gives per-screen
     VMs like `CollectionMeasurementsViewModel` (the cards).
   - Host build.gradle needs `androidx.hilt:hilt-navigation-compose` for
     `hiltViewModel()` (import from `androidx.hilt.lifecycle.viewmodel.compose`).
3. Wire it: `setContent { MbPulseTheme { MbPulseNavHost(measurementsScreen =
   { ... -> MyScreen(...) }) } }` — every slot you don't name keeps its
   default. (In the template: swap `MbPulseApp()` for `AppWithCustomHome()`.)
4. Honour the callbacks — navigation stays library-owned: call
   `onNavigateToCollection()` etc. rather than navigating yourself.

## Verification checklist

- [ ] `.\gradlew.bat assembleDebug` (in the app's folder) succeeds.
- [ ] The replaced screen shows your UI; all other screens are unchanged
      library screens.
- [ ] Navigation still works end-to-end: study selection → onboarding → your
      screen → collection → back; history opens from wherever you call
      `onCardClick`.
- [ ] Live data still flows: start a replay collection — your screen reflects
      recording state and values (proves the ViewModels are wired, not copied).
- [ ] Process death: background the app, kill it from the task switcher,
      reopen — the flow resumes without crashing (Nav 3 restores the stack).
