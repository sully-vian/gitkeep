# gitkeep
A simple git-based note-taking mobile app

## Note format

Every note is one Markdown file in the git repository, with a YAML front matter header for its metadata.

### File name

The file name is the note's creation time, in local time: `YYYYMMDD-HHMMSS.md`, for example `20261117-143052.md`.

- It never changes after creation, even when the title does. Renaming files would add noise to the git history and cause conflicts.
- If the name already exists, add a suffix: `20261117-143052-2.md`.
- The file name is the note's identity (`path` in the app). The title is only metadata.

### Content

```markdown
---
title: "Meeting with the team"
tags: ["work", "meetings"]
created: "2026-11-17T14:30:52+01:00"
---

The note's body, in Markdown.
```

- The header starts at the very first line with `---` and ends at the next `---` line.
- `title`: string. Can be empty.
- `tags`: list of strings, without `#`. Can be omitted when empty.
- `created`: full ISO 8601 timestamp with timezone. Set once, never changed.
- The body is everything after the closing `---`, without the blank line that follows it.
- Files are UTF-8, with LF line endings and a final newline.
- A file without a header is still a valid note: empty title, no tags, creation time unknown.

### What is *not* stored in the file

- **Modification time.** Git already records it (`git log -1 --format=%cI -- <file>`). A `modified:` line rewritten on every save would make every note edited on two devices conflict on that line, even when the edits themselves are in different paragraphs that git could merge on its own.

### Writing rules

So that saving a note without changes produces an identical file, and no git diff:

- Keys are written in this order: `title`, `tags`, `created`, then any other keys.
- Strings are always double-quoted. Unquoted YAML values are reinterpreted by parsers: `2026-11-17` becomes a date, and `no` or `yes` become booleans in YAML 1.1 parsers such as SnakeYAML.
- Unknown keys (added by hand or by another tool, e.g. `aliases:`) are kept and written back unchanged, never dropped.

### In the app

The web app works with notes as objects:

```js
{
    path: "20261117-143052.md", // identity: which file to write or delete
    title: "Meeting with the team",
    tags: ["work", "meetings"],
    createdAt: "2026-11-17T14:30:52+01:00",
    modifiedAt: "2026-11-18T09:12:00+01:00", // read-only, from git; never written to the file
    extra: { aliases: ["standup"] }, // unknown header keys, kept as-is
    content: "The note's body, in Markdown.\n",
}
```

### Where the conversion happens: in JavaScript, not in Java

The native plugin only moves raw files: it reads and writes `{ path, text }`, and handles git. One JavaScript module converts between the raw file text and the note object above.

Parsing the header in Java instead would mean:

- **Two parsers to keep identical.** The browser mock (used for all UI work in desktop Chrome) must return exactly what the plugin returns, so the conversion would have to exist in both Java and JavaScript. Two YAML libraries with different edge cases, for example around dates, booleans and quoting, would drift apart, and the app would behave differently in the browser and on the phone.
- **Slower iteration on the fiddliest part.** Front matter edge cases are best fixed with instant reloads in the browser, not with a rebuild and reinstall on the phone for each try.
- **A bigger plugin for no gain.** The plugin's real job, git (commit, pull, merge, push, conflicts), is already the hardest part of the app. The background sync worker never needs titles or tags, so parsing in Java would add code there without giving it anything.
