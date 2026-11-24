# Git-based Markdown Notes App — Roadmap (native Java)

A minimal Android note-taking app inspired by GitJournal: plain Markdown files stored in a git repo, synced with a remote host.

This branch (`java-only`) replaces the Capacitor + WebView version with a native Android app written entirely in Java.

## Stack

- **Language:** Java only, no Kotlin. Gradle build scripts in Groovy (`build.gradle`), not the Kotlin DSL (`.kts`).
- **UI:** Android Views with Material Components: `RecyclerView` for the list, `EditText` for the editor, `Toolbar` and menus. No Jetpack Compose (it requires Kotlin).
- **Markdown preview:** Markwon (renders Markdown into a `TextView`, no WebView). Fallback if it no longer fits current Android versions: commonmark-java, with the HTML shown in a WebView that has JavaScript disabled.
- **Note format:** YAML front matter + Markdown body, as described in `README.md`. Parsed with SnakeYAML.
- **Git:** JGit. JSch (the maintained `com.github.mwiede:jsch` fork) for SSH, optional.
- **Background sync:** WorkManager.
- **Tests:** JUnit, run on the JVM (`./gradlew test`), no device needed.
- **Tooling:** neovim + jdtls, `./gradlew` and `adb`, wrapped in a Makefile. No Android Studio required.

## Architecture

- **No server anywhere in the app.** The only remote server is the git host.
- **Logic in plain Java classes,** with no `android.*` imports, so they can be unit-tested on the JVM:
  - `NoteFormat`: file text ↔ `Note` object (front matter + body).
  - `NoteStore`: reads, writes and deletes note files in a folder.
  - `GitRepo`: wraps JGit (init, commit, clone, pull, push, conflicts).
  - `SyncManager`: commit → pull → push, with a lock so that two syncs never run at once.
- **Android classes stay thin:** Activities, adapters and the WorkManager worker only call the classes above.

Every phase ends with something that runs on the phone. Git is introduced gradually, so the UI and JGit are never debugged at the same time.

---

## Phase 0 — Setup

- [x] Install the Android SDK command-line tools and a JDK supported by the Android build tools (JDK 21)
- [x] Enable Developer options and USB debugging on the phone
- [x] Remove the Capacitor project: `android/`, `www/`, `package.json`, `bun.lock`, `capacitor.config.json`, `node_modules/`
- [x] Write a minimal Gradle project by hand: `settings.gradle`, root and app `build.gradle`, `gradle.properties`, Gradle wrapper, `AndroidManifest.xml`, one `MainActivity` with one layout saying "Hello"
- [x] Keep the app ID: `com.sully_vian.gitkeep`
- [x] Add one JUnit test that passes
- [x] Rewrite the Makefile: `build`, `install`, `run` (install + launch), `test`, `logs`, `clean`
- [x] Set up jdtls in neovim for the project

**Done when:** "Hello" opens as a native app on the phone, and `make test` runs the JUnit test.

## Phase 1 — Note format (pure Java, no UI)

- [x] Copy `fixtures/` into `app/src/test/resources/fixtures/` (or point the tests at the top-level folder)
- [x] `Note` class: `path`, `title`, `tags`, `createdAt`, `extra` (unknown header keys), `content` (body)
- [x] `NoteFormat.parse(path, text)`: header delimited by `---` on the first line, body without the blank line after it, a file without a header is valid
- [x] `NoteFormat.serialize(note)`: fixed key order, strings double-quoted, unknown keys kept, LF line endings, final newline
- [x] Tests over every fixture:
  - parsing gives the expected title, tags and date
  - unquoted values (`title: no`, `tags: [yes, 2026]`, `created: 2026-11-16`) are read as strings
  - `---` lines in the body are not taken for the end of the header
  - parse → serialize gives back an identical file for files that already follow the writing rules
- [x] Decide what happens to comments in the header (SnakeYAML drops them when writing): accept the loss, or rewrite only the known keys in the original header text

**Done when:** `make test` passes for all fixtures, including the edge cases.

## Phase 2 — Local notes and UI (no git yet)

Notes live in `getFilesDir()/notes`, a flat folder of `.md` files. One step per commit; steps 1 to 3 are pure Java in `core`, with JVM tests against a temporary folder.

1. [x] `NoteStore` reading: constructor takes the folder; `list()` parses every `.md` file (other files and subfolders ignored), `read(path)` parses one. A file that can't be read is skipped by `list()`, never fatal.
2. [x] `NoteStore` writing: `write(note)` serializes to a temp file in the same folder, then renames it over the note (atomic: a crash never leaves half a note). `delete(path)`.
3. [x] New note names: `newPath(createdAt)` gives `YYYYMMDD-HHMMSS.md` in local time, with `-2`, `-3`… when the name is taken. Tests with a fixed time and existing files.
4. [x] Debug fixtures: the top-level `fixtures/` folder packaged as assets of debug builds only (Gradle, no copy committed). On launch, if the notes folder is empty, copy them in. Release builds have no such assets, so the copy does nothing. Check with `adb shell run-as com.sully_vian.gitkeep ls files/notes`.
5. [x] Note list: `MainActivity` shows a `RecyclerView` of titles (file name when the title is empty), newest first by `createdAt`, then by file name. Files read on a background thread, the list updated on the main thread.
6. [x] Open a note: tapping a row opens `NoteActivity` with the note's path (replaces `EXTRA_TITLE`). Title field + monospace body field, filled from the file. No saving yet.
7. [ ] Save: a few seconds after the last keystroke, and always in `onPause`, only when the title or body changed. Written off the main thread. The list reloads when it comes back to the foreground.
8. [ ] Create a note: a "New" button opens an empty editor; the file is created on the first save, with `created` set then. A new note left empty is never written.
9. [ ] Delete: a menu item in the editor, with a confirmation dialog; back to the list afterwards.

**Done when:** notes can be created, edited and deleted on the phone, and are still there after force-closing the app.

## Phase 3 — Markdown preview

- [ ] Add Markwon; check that it builds and runs with the current SDK, otherwise switch to the commonmark-java fallback
- [ ] Edit / Preview toggle in the editor
- [ ] Raw HTML in notes is never rendered or executed (check with the XSS fixture, title included)
- [ ] Code blocks highlighted, unknown languages shown as plain text
- [ ] Links open in the external browser
- [ ] Optional: Markdown highlighting inside the `EditText` while typing (Markwon's editor module)

**Done when:** every fixture previews correctly, and the XSS fixture shows only plain text.

## Phase 4 — Local git

- [ ] Add JGit to `app/build.gradle`; check the JGit version against the Android SDK level (newer JGit versions need recent Java APIs, which may require core library desugaring)
- [ ] `GitRepo`: initialize a repo in the notes folder on first launch, with a `.gitattributes` forcing LF line endings
- [ ] Commit on save, debounced: after a few seconds without typing, or when leaving the editor
- [ ] Author name and email from settings, with a default until Phase 5
- [ ] `modifiedAt` from `git log -1` per file (never stored in the file)
- [ ] JVM tests for `GitRepo` against a temporary folder
- [ ] Optional: hidden "history" screen showing the log of a note

**Done when:** pulling the notes folder off the device (`adb`) gives a valid git history.

## Phase 5 — Remote sync (HTTPS + token, manual)

- [ ] Settings screen: repo URL, username, personal access token, author name and email
- [ ] Store the token encrypted with an Android Keystore key, never in plain `SharedPreferences`
- [ ] First setup: clone into an empty folder, or connect the existing local repo to the remote (fetch, then merge or replace; decide which wins)
- [ ] `SyncManager.sync()`: commit → pull (merge) → push
- [ ] Sync button and status line ("Synced 2 min ago", "Offline", "Error: …")
- [ ] Reload the list and the open note after a pull changed them
- [ ] JVM tests for sync against local bare repos

**Done when:** an edit on the phone shows up on GitHub/Gitea, and an edit made on the computer shows up on the phone after tapping Sync.

## Phase 6 — Conflicts

- [ ] Simulate conflicts on purpose: same line edited on both sides, delete on one side and edit on the other, a sync interrupted halfway
- [ ] Strategy that never blocks writing: keep the local version as the note, save the remote version as a separate conflict note (`<name>.conflict-<date>.md`), commit both, push
- [ ] Badge on notes that have conflicts
- [ ] Recover from a repo left in a merge state
- [ ] JVM tests for each conflict case

**Done when:** no combination of edits on both sides leaves the repo stuck or loses text.

## Phase 7 — Tags, search and sorting

- [ ] Tags from the front matter; inline `#tags` in the body (careful with `C#`, `#42`, URL anchors and code, see the fixtures)
- [ ] Filter by tag, combined with search
- [ ] Search in titles and bodies (`SearchView`), case-insensitive, with a snippet around the match
- [ ] Sort by modification date or creation date
- [x] Folders, or keep a flat structure: flat, notes at the root, subfolders ignored

**Done when:** the app is used for real notes for a week without missing anything important.

## Phase 8 — Automatic sync

- [ ] Sync when the app comes to the foreground and when it goes to the background
- [ ] WorkManager periodic job (minimum interval: 15 minutes), only with network
- [ ] The same lock in `SyncManager` for the worker, the UI and the commits

**Done when:** the Sync button is never pressed manually anymore.

## Phase 9 — SSH (optional)

- [ ] Generate an SSH key in the app, private key stored with the Keystore
- [ ] Show the public key with a copy button, to add as a deploy key on the git host
- [ ] Configure JGit's SSH transport with that key

**Done when:** cloning and syncing work over `git@…` URLs.

## Phase 10 — Polish and release

- [ ] Dark mode (`DayNight` theme)
- [ ] Empty states
- [ ] App icon and splash screen
- [ ] Export or open the notes folder, as a way out when sync is broken
- [ ] Release signing key (keep a backup!) and release APK, without the debug fixtures
- [ ] Choose distribution: direct APK, F-Droid, or Play Store

---

## Habits to keep throughout

- Keep logic out of Activities: anything that can be a plain Java class is one, with JVM tests. The UI loop on the phone is slow; the test loop on the JVM is fast.
- Use the fixtures as test data, and add a fixture for every bug found in the note format.
- Use a throwaway test repo until Phase 6 is solid, not real notes.
- Phases 5 and 6 are the hardest. Give conflicts time.

## Security reminders

- Never render raw HTML from notes. If a WebView is ever used for the preview, keep JavaScript disabled and never load remote pages.
- Keep credentials (token, SSH private key) encrypted with the Android Keystore.
- Links in notes open in the external browser, never inside the app.
