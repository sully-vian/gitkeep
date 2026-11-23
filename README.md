# gitkeep
A simple git-based note-taking app for Android, written in Java

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
- Unknown keys (added by hand or by another tool, e.g. `aliases:`) are kept and written back with the same values, never dropped. Their layout follows the rules above: a list written over several lines (`- a`) comes back as `["a"]`.

### In the app

The app works with notes as `Note` objects:

```java
public record Note(
    String path,                // "20261117-143052.md": identity, which file to write or delete
    String title,               // "Meeting with the team"
    List<String> tags,          // ["work", "meetings"]
    OffsetDateTime createdAt,   // 2026-11-17T14:30:52+01:00
    OffsetDateTime modifiedAt,  // from git; never written to the file
    Map<String, Object> extra,  // unknown header keys (e.g. aliases), kept as-is, in their original order
    String content) {}          // the body, in Markdown
```

A `Note` is immutable: changing a note means building a new one.

`createdAt` is `null` when the header has no usable `created` value (no header, or a hand-written date without a time).

### Where the conversion happens

One class, `NoteFormat`, converts between a file's text and a `Note`:

- `NoteFormat.parse(path, text)` returns a `Note`.
- `NoteFormat.serialize(note)` returns the file's text, following the writing rules above.

It uses SnakeYAML for the header and has no Android dependencies, so it runs in plain JVM unit tests (`./gradlew test`) against every file in `fixtures/`. It's the only place that knows the file format: the UI, the note store, git and sync all work with `Note` objects or raw text, never with YAML.

Comments in the header are not kept: `serialize` rebuilds the header from the `Note`, so a comment written by hand is lost the first time the app saves that note. To keep a remark in the header, use a key instead, e.g. `comment: "..."`.
