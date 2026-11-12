# Git-based Markdown Notes App — Roadmap

A minimal Android note-taking app inspired by GitJournal: plain Markdown files stored in a git repo, synced with a remote host.

## Stack

- **App shell:** Capacitor (Android only)
- **Web UI (static files):** HTML + CSS + Alpine.js + marked
- **Editor:** plain `<textarea>`
- **Search & tags:** hand-written, in memory
- **Native plugin (Java):** JGit for git, file I/O, WorkManager for background sync, JSch for SSH (optional)
- **Architecture:** no server in the app — the WebView calls the Java plugin through Capacitor's bridge; the only remote server is the git host

Every phase ends with something that runs on the phone. Git is introduced gradually so the UI, the bridge and JGit are never debugged all at once.

---

## Phase 0 — Setup

- [x] Install Node.js and Android Studio (includes the Android SDK and a JDK)
- [x] Enable Developer options and USB debugging on the phone
- [x] Create the project: `npm init`, install `@capacitor/core`, `@capacitor/cli`, `@capacitor/android`
- [x] Run `npx cap init` with `webDir` set to `www`
- [x] Add a `www/index.html` saying "Hello"
- [x] Run `npx cap add android` and `npx cap run android`
- [x] Put the project itself under git

**Done when:** the "Hello" page opens as an app on the phone and can be inspected via `chrome://inspect`.

## Phase 1 — UI prototype (desktop browser, no Java)

- [ ] Add `alpine.min.js` and `marked` to `www/lib/`
- [ ] Write a mock `GitSync` in JS (`loadAll`, `saveNote`, `deleteNote`) backed by memory or `localStorage`, used only when `window.Capacitor` is missing
- [ ] Note list
- [ ] Search box (`includes()` filter)
- [ ] `<textarea>` editor
- [ ] Markdown preview with raw HTML escaped
- [ ] Create / delete note
- [ ] Decide the note format: `my-note.md`, optional front matter `tags: [a, b]`, inline `#tags`

**Done when:** notes can be created, edited, searched and previewed in desktop Chrome.

## Phase 2 — Native file storage (no git yet)

- [ ] Create `GitSyncPlugin.java` in `android/app/src/main/java/...`
- [ ] Register it in `MainActivity` with `registerPlugin(GitSyncPlugin.class)`
- [ ] Implement `loadAll`, `saveNote`, `deleteNote` over `getContext().getFilesDir()/notes`, on a background thread
- [ ] Use the real plugin on the phone, the mock in the browser

**Done when:** notes written on the phone are still there after force-closing the app.

## Phase 3 — Local git

- [ ] Add the JGit dependency to `android/app/build.gradle`
- [ ] Initialize a repo in the notes folder on first launch
- [ ] Commit on save, debounced (after a few seconds without typing, or when leaving a note)
- [ ] Optional: hidden "history" screen showing `git log` for a note

**Done when:** pulling the notes folder off the device (e.g. with `adb`) gives a valid git history.

## Phase 4 — Remote sync (HTTPS + token, manual)

- [ ] Settings screen: repo URL, username, personal access token, author name/email
- [ ] Store the token securely on the native side (Android Keystore), not in `localStorage`
- [ ] Implement `clone` (first setup) and `sync`: commit → pull (merge) → push
- [ ] Sync button and status line ("Synced 2 min ago", "Offline", "Error: …")

**Done when:** an edit on the phone shows up on GitHub/Gitea, and an edit made on the computer shows up on the phone after tapping Sync.

## Phase 5 — Conflicts

- [ ] Simulate a conflict on purpose (edit the same line on both sides, then sync)
- [ ] Strategy that never blocks writing: keep the local version as the note, save the remote version as `note.conflict-<date>.md`, commit both, push
- [ ] Badge on notes that have conflicts

**Done when:** no combination of edits on both sides leaves the repo stuck or loses text.

## Phase 6 — Tags and search improvements

- [ ] Parse tags from front matter and inline `#tags` into a tag list
- [ ] Filter by tag, combined with search
- [ ] Sort by modification date
- [ ] Show search matches with a snippet of surrounding text
- [ ] Folders, or keep a flat structure

**Done when:** the app is used for real notes for a week without missing anything important.

## Phase 7 — Automatic sync

- [ ] Sync when the app opens and when it goes to the background (`handleOnResume` / `handleOnPause` in the plugin, no `@capacitor/app` dependency)
- [ ] WorkManager periodic job for background sync (minimum interval: 15 minutes), only when the network is available
- [ ] Lock in the plugin so two syncs can't run at the same time

**Done when:** the Sync button is never pressed manually anymore.

## Phase 8 — SSH (optional)

- [ ] Generate an SSH key in the app
- [ ] Display the public key with a copy button, to be added as a deploy key on the git host
- [ ] Configure JGit's SSH transport with that key

**Done when:** cloning and syncing work over `git@…` URLs.

## Phase 9 — Polish and release

- [ ] Dark mode
- [ ] Android back button handling
- [ ] App icon and splash screen
- [ ] Empty states
- [ ] Release signing key (keep a backup!) and release APK
- [ ] Choose distribution: direct APK, F-Droid, or Play Store

---

## Habits to keep throughout

- Keep the mock `GitSync` working so UI work can always happen in desktop Chrome with instant reloads.
- Use a throwaway test repo until Phase 5 is solid, not real notes.
- Phases 4 and 5 are the hardest; everything before them is a few evenings of work. Give conflicts time.

## Security reminders

- Any JavaScript in the WebView can call the plugin, so escape raw HTML in rendered Markdown (or use DOMPurify).
- Never load remote web pages inside the app's WebView.
- Keep credentials (token, SSH private key) on the native side only.
