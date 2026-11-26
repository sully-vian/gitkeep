# gitkeep

[![CI](https://github.com/sully-vian/gitkeep/actions/workflows/ci.yml/badge.svg)](https://github.com/sully-vian/gitkeep/actions/workflows/ci.yml)

A simple git-based note-taking app for Android, written in Java

## Note format

Every note is one Markdown file in the git repository, with a YAML front matter header for its metadata.

### File name

The file name is the note's creation time, in local time: `YYYYMMDD-HHMMSS.md`, for example `20261117-143052.md`.

- It never changes after creation, even when the title does. Renaming files would add noise to the git history and cause conflicts.
- If the name already exists, add a suffix: `20261117-143052-2.md`.
- The file name is the note's identity (`path` in the app). The title is only metadata.
- Notes live at the root of the repository. Subfolders are ignored: the app never shows, changes or deletes the files in them. Tags take the role of folders.

### Content

```markdown
---
title: "Meeting with the team"
tags: ["work", "meetings"]
---

The note's body, in Markdown.
```

- The header starts at the very first line with `---` and ends at the next `---` line.
- `title`: string. Can be empty.
- `tags`: list of strings, without `#`. Can be omitted when empty.
- The body is everything after the closing `---`, without the blank line that follows it.
- Files are UTF-8, with LF line endings and a final newline.
- A file without a header is still a valid note: empty title, no tags.

### What is *not* stored in the file

Dates. The header holds what only the note can say: its title and tags.

- **Creation time.** A note made by the app is named after it. For other notes, git knows when the file first appeared (`git log --diff-filter=A --format=%aI -- <file>`). A `created:` line already in a file is kept as an unknown key, but the app doesn't read it.
- **Modification time.** Git already records it (`git log -1 --format=%cI -- <file>`). A `modified:` line rewritten on every save would make every note edited on two devices conflict on that line, even when the edits themselves are in different paragraphs that git could merge on its own.

The app reads these dates from the history: the whole history once, then only the new commits. The list shows when each note was last edited, as of its last commit; a note never committed yet shows no date.

### Writing rules

So that saving a note without changes produces an identical file, and no git diff:

- Keys are written in this order: `title`, `tags`, then any other keys.
- Strings are always double-quoted. Unquoted YAML values are reinterpreted by parsers: `2026-11-17` becomes a date, and `no` or `yes` become booleans in YAML 1.1 parsers such as SnakeYAML.
- Unknown keys (added by hand or by another tool, e.g. `aliases:`) are kept and written back with the same values, never dropped. Their layout follows the rules above: a list written over several lines (`- a`) comes back as `["a"]`.

### In the app

The app works with notes as `Note` objects:

```java
public record Note(
    String path,                          // "20261117-143052.md": identity, which file to write or delete
    String title,                         // "Meeting with the team"
    List<String> tags,                    // ["work", "meetings"]
    Map<String, @Nullable Object> extra,  // unknown header keys (e.g. aliases), kept as-is, in their original order
    String content) {}                    // the body, in Markdown
```

A `Note` is immutable: changing a note means building a new one. Nothing in it is `null`: an empty title, tag list or body is an empty value.

### Where the conversion happens

One class, `NoteFormat`, converts between a file's text and a `Note`:

- `NoteFormat.parse(path, text)` returns a `Note`.
- `NoteFormat.serialize(note)` returns the file's text, following the writing rules above.

It uses SnakeYAML for the header and has no Android dependencies, so it runs in plain JVM unit tests (`./gradlew test`) against every file in `fixtures/`. It's the only place that knows the file format: the UI, the note store, git and sync all work with `Note` objects or raw text, never with YAML.

Comments in the header are not kept: `serialize` rebuilds the header from the `Note`, so a comment written by hand is lost the first time the app saves that note. To keep a remark in the header, use a key instead, e.g. `comment: "..."`.

## Git library

The app uses [JGit](https://www.eclipse.org/jgit/) **5.13.x**, the last version built for Java 8, not the current 7.x.

- **Why not 7.x:** it calls Java methods that Android only has from API 33 (`InputStream.readAllBytes`, `readNBytes`…), and neither Android's build tools nor its desugaring library provide them. One of them is on the path of `git add` whenever a file's line endings are converted (`.gitattributes` with `eol=lf`), so it would crash on phones older than Android 13, while `minSdk` is 26 (Android 8).
- **How this was checked:** every Java method that JGit's code in the APK calls was looked up in Android's own API table (`platforms/android-*/data/api-versions.xml`). With 5.13.5, nothing needs more than API 26. Then a real init, add, commit, status and log ran on an Android 12 phone, and the resulting repository passed `git fsck`.
- **What 5.13.x still lacks on Android:** Java's monitoring classes and process handles, used only by JGit's garbage collector and statistics. The app turns automatic garbage collection off (`gc.auto = 0`).
- **Moving to a newer JGit** means running the same check again, or raising `minSdk` to 33.

## Git repository

The notes folder is the work tree of a git repository, with `.git` inside it. The app creates it on branch `main`, with a first commit, "Set up the repository", holding two files:

- `.gitattributes`: `* text=auto eol=lf`. Text files are stored with LF line endings, even when a note was edited with CRLF on another computer; binary files are left alone.
- `.gitignore`: `/.*.tmp`, the temp files of a note being saved, which only a crash leaves behind.

Each time the repository is opened, also one cloned or made by hand:

- automatic garbage collection is turned off (`gc.auto = 0`), see above;
- a missing `.gitattributes` or `.gitignore` is written back, and committed with the next commit;
- a `.git/index.lock` is deleted. The app is the only program writing to the repository, from a single thread, so a lock found then was left by a commit killed halfway, and would make every later commit fail.

### Commits

A commit takes every file of the folder's root that was added, edited or deleted since the last one, notes or not. Files in subfolders are never staged, like everywhere else in the app. When nothing changed, there is no commit.

The app commits when the editor is left (Back, Home, another app; not a rotation, and not on each autosave while typing), once the "Undo" of a delete is gone, and at launch, for the fixtures of a debug build or anything left uncommitted by a crash. The author is `gitkeep <gitkeep@localhost>` until it can be set in the settings.

Messages name the files, never their titles or content, so the history can be shared for troubleshooting: `Edit 20261124-015725.md` for one file, and for several, a summary line and one line per file:

```
Edit 2 notes, delete 1

Edit 20261124-015725.md
Edit 20261125-080000.md
Delete 20261101-090000.md
```

## Sync

The repository is synced with a remote host over HTTPS, with a username and a personal access token, by `SyncManager`. A sync:

1. commits whatever is pending;
2. fetches the remote (`origin`);
3. merges its branch into the local one: a fast-forward when the phone has nothing new, else a merge commit, "Merge remote changes", by the app's author;
4. pushes when the phone has commits the remote doesn't, and when the remote moved in the meantime (another device pushed), fetches, merges and pushes once more.

Fetching and pushing run on a thread of their own, committing and merging on the thread that saves the notes: saving a note never waits for the network, and a merge never runs while a note is being written. Only one sync runs at a time.

A sync ends with one of: done, offline (the host can't be reached), credentials refused, conflict, or another error with a short explanation. Credentials written in the URL (`https://user:token@…`) are refused: they would be saved in the repository's config in clear. The token is never logged; the sync's own log lines name the host only.

### In the app

The Sync button is in the list's app bar; while the remote isn't set up, it opens the settings. The app bar's subtitle tells how the last sync ended: "Synced 2 minutes ago", "Syncing…", "Offline" (checked before trying, so no time is lost waiting for the network), "Error: wrong username or token", "Error: conflict in …", or "Error: …". The list reloads after every sync. Sync only starts from the list, so no note is open while a sync changes the notes.

### Settings and the token

The settings screen (list menu → Settings) holds the repository's HTTPS URL, the username, the personal access token, and the author of the commits. The author applies to the next commit; left empty, it is `gitkeep <gitkeep@localhost>`.

Everything but the token is saved in clear in the app's preferences: none of it is secret. The token is encrypted (AES-256-GCM) with a key of the Android Keystore, which never leaves it and only this app can use, and only the encrypted token and its IV are saved, in a separate file. A token that can't be decrypted, e.g. after the app's files were restored on another phone without the key, counts as not set and has to be typed again. The settings screen only says whether a token is set.

### Conflicts (until conflict notes exist)

When both sides changed the same file (the same note edited on two devices, or edited on one and deleted on the other), the merge is undone at once: the repository goes back to its last commit, which holds everything the phone had, nothing is pushed, and the sync ends with "conflict" and the names of the files. The phone keeps working as before, and every sync stops on the same conflict until one side gives up its change. A merge left unfinished by a sync killed halfway is undone the same way when the repository is opened.

Everything pending is committed right before the merge, in the same task, so that undoing it never loses a note saved while the sync was fetching.

### First sync with a remote

The first sync with a remote (or with a new URL: what was known of the old remote is forgotten) connects the two sides:

- The local branch takes the name of the remote's default branch (`main`, `master`…), so that both sides talk about the same branch. Upstream settings are written, so that git on a computer, given a copy of the repository, knows what to pull.
- The remote is empty: the phone's history is pushed.
- The phone has nothing of its own yet, only the setup commit's files: it takes the remote's files as they are, with its history, then adds the setup files if the remote lacks them, and pushes that commit.
- Both sides have files: the two histories are merged (names made by the app are creation times, so two notes rarely share a name), then pushed.
