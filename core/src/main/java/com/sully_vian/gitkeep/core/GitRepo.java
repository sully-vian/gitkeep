package com.sully_vian.gitkeep.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.logging.Logger;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * The git repository of the notes folder: the folder is its work tree, with the
 * repository in ".git" inside it (hidden, so {@link NoteStore} never lists it).
 *
 * <p>
 * Blocking file I/O, and not thread-safe: use it from one thread only. The
 * exception is {@link SyncManager}'s fetch and push, which may run on another
 * thread at the same time: they only touch git's objects and remote-tracking
 * branches, never the work tree, the index or the local branch.
 *
 * <p>
 * Logs file names only, never what's in the notes.
 */
public final class GitRepo implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(GitRepo.class.getName());

    /** The branch of a new repository. */
    static final String BRANCH = "main";

    static final String ATTRIBUTES = ".gitattributes";
    static final String IGNORE = ".gitignore";

    /**
     * Text files stored with LF line endings, whatever the editor that wrote them
     * (a note edited on Windows comes back with CRLF). "text=auto" leaves binary
     * files alone.
     */
    static final String ATTRIBUTES_TEXT = "# Written by gitkeep: text files are stored with LF line endings\n"
            + "* text=auto eol=lf\n";

    /** The temp files of {@link NoteStore#write}, left behind only by a crash. */
    static final String IGNORE_TEXT = "# Written by gitkeep: temp files of notes being saved\n"
            + "/.*.tmp\n";

    static final String SETUP_MESSAGE = "Set up the repository";

    private final Path dir;
    private final Git git;
    private volatile String authorName;
    private volatile String authorEmail;
    private final Clock clock;
    private final GitDates dates = new GitDates();

    private GitRepo(Path dir, Git git, String authorName, String authorEmail, Clock clock) {
        this.dir = dir;
        this.git = git;
        this.authorName = authorName;
        this.authorEmail = authorEmail;
        this.clock = clock;
    }

    /**
     * Opens the repository of a notes folder, or creates it (with the folder)
     * when there is none: on branch {@value #BRANCH}, with a first commit holding
     * {@value #ATTRIBUTES} and {@value #IGNORE}.
     *
     * <p>
     * Every time, also for a repository made elsewhere: automatic garbage
     * collection is turned off (it needs Java classes Android doesn't have),
     * missing {@value #ATTRIBUTES} or {@value #IGNORE} files are written back
     * (committed with the next commit), and a lock left by a commit that was
     * killed halfway is removed.
     *
     * @param authorName  author and committer of the app's commits
     * @param authorEmail their email
     * @param clock       the time of the commits, and its time zone
     */
    public static GitRepo open(Path dir, String authorName, String authorEmail, Clock clock)
            throws IOException, GitAPIException {
        Path gitDir = dir.resolve(Constants.DOT_GIT);
        boolean created = false;
        Git git;
        if (Files.isDirectory(gitDir)) {
            // the ".git" of this folder only, never one of a parent folder
            git = Git.open(dir.toFile());
        } else {
            Files.createDirectories(dir);
            git = Git.init()
                    .setDirectory(dir.toFile())
                    .setInitialBranch(BRANCH)
                    .call();
            created = true;
            LOG.info("repository created: " + dir);
        }

        GitRepo repo = new GitRepo(dir, git, authorName, authorEmail, clock);
        try {
            repo.removeStaleLock(gitDir);
            repo.turnOffGc();
            List<String> written = repo.writeMissingSetupFiles();
            if (created) {
                repo.commitFiles(SETUP_MESSAGE, written, new ArrayList<>());
            }
        } catch (IOException | GitAPIException | RuntimeException e) {
            repo.close();
            throw e;
        }
        return repo;
    }

    /**
     * The app is the only writer, from a single thread: a lock found when opening
     * was left by a commit killed halfway, and would make every later commit
     * fail.
     */
    private void removeStaleLock(Path gitDir) throws IOException {
        if (Files.deleteIfExists(gitDir.resolve("index.lock"))) {
            LOG.warning("removed a stale index.lock");
        }
    }

    private void turnOffGc() throws IOException {
        StoredConfig config = git.getRepository().getConfig();
        if (config.getInt(ConfigConstants.CONFIG_GC_SECTION, ConfigConstants.CONFIG_KEY_AUTO, -1) != 0) {
            config.setInt(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_AUTO, 0);
            config.save();
        }
    }

    /**
     * Writes {@value #ATTRIBUTES} and {@value #IGNORE} when they're missing, e.g.
     * after taking a remote's files that don't have them. Not committed.
     *
     * @return the names of the files written
     */
    List<String> writeMissingSetupFiles() throws IOException {
        List<String> written = new ArrayList<>();
        writeIfMissing(ATTRIBUTES, ATTRIBUTES_TEXT, written);
        writeIfMissing(IGNORE, IGNORE_TEXT, written);
        return written;
    }

    private void writeIfMissing(String name, String text, List<String> written) throws IOException {
        Path file = dir.resolve(name);
        if (Files.exists(file)) {
            return;
        }
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        written.add(name);
        LOG.info("wrote " + name);
    }

    /**
     * Commits every file of the folder's root that was added, edited or deleted
     * since the last commit. Files in subfolders are never staged, and ignored
     * files (temp files) never seen.
     *
     * <p>
     * The message names the files, never their content: "Edit
     * 20261124-015725.md" for one file, or a summary line followed by one line
     * per file.
     *
     * @return the new commit, or null when nothing changed
     */
    public @Nullable RevCommit commit() throws IOException, GitAPIException {
        Set<String> changed = new TreeSet<>();
        Status status = git.status().call();
        addRootPaths(changed, status.getUntracked());
        addRootPaths(changed, status.getAdded());
        addRootPaths(changed, status.getModified());
        addRootPaths(changed, status.getChanged());
        addRootPaths(changed, status.getMissing());
        addRootPaths(changed, status.getRemoved());
        if (changed.isEmpty()) {
            return null;
        }

        // what the change is, from the last commit and the folder (the index may
        // hold the staged half of an interrupted commit)
        List<String> added = new ArrayList<>();
        List<String> edited = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> unstaged = new ArrayList<>();
        Set<String> inHead = headPaths(changed);
        for (String path : changed) {
            boolean exists = Files.exists(dir.resolve(path));
            if (inHead.contains(path)) {
                (exists ? edited : deleted).add(path);
            } else if (exists) {
                added.add(path);
            } else {
                // staged, then deleted before any commit: nothing to say about it
                unstaged.add(path);
            }
        }

        List<String> toAdd = new ArrayList<>(added);
        toAdd.addAll(edited);
        List<String> toRemove = new ArrayList<>(deleted);
        toRemove.addAll(unstaged);
        if (toAdd.isEmpty() && deleted.isEmpty()) {
            stage(toAdd, toRemove);
            return null;
        }
        return commitFiles(message(added, edited, deleted), toAdd, toRemove);
    }

    /** Only the files at the root: a path with a "/" is in a subfolder. */
    private static void addRootPaths(Set<String> into, Set<String> paths) {
        for (String path : paths) {
            if (path.indexOf('/') == -1) {
                into.add(path);
            }
        }
    }

    /** Which of the given root paths are in the last commit. */
    private Set<String> headPaths(Set<String> paths) throws IOException {
        Set<String> found = new HashSet<>();
        Repository repository = git.getRepository();
        ObjectId head = repository.resolve(Constants.HEAD + "^{tree}");
        if (head == null) {
            // no commit yet
            return found;
        }
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(head);
            // not recursive: only the root's entries
            walk.setRecursive(false);
            while (walk.next()) {
                if (paths.contains(walk.getPathString())) {
                    found.add(walk.getPathString());
                }
            }
        }
        return found;
    }

    /**
     * "Edit 20261124-015725.md" for one file. For several, a summary line, then
     * one line per file, added first, then edited, then deleted:
     *
     * <pre>
     * Edit 2 notes, delete 1
     *
     * Edit 20261124-015725.md
     * Edit 20261125-080000.md
     * Delete 20261101-090000.md
     * </pre>
     *
     * "notes" when every file is a ".md", "files" otherwise.
     */
    static String message(List<String> added, List<String> edited, List<String> deleted) {
        List<String> lines = new ArrayList<>();
        for (String path : added) {
            lines.add("Add " + path);
        }
        for (String path : edited) {
            lines.add("Edit " + path);
        }
        for (String path : deleted) {
            lines.add("Delete " + path);
        }
        if (lines.size() == 1) {
            return lines.get(0);
        }

        boolean allNotes = true;
        for (List<String> group : Arrays.asList(added, edited, deleted)) {
            for (String path : group) {
                allNotes &= path.endsWith(".md");
            }
        }
        List<String> parts = new ArrayList<>();
        int firstCount = addPart(parts, "add", added.size(), 0);
        firstCount = addPart(parts, "edit", edited.size(), firstCount);
        firstCount = addPart(parts, "delete", deleted.size(), firstCount);
        // the noun after the first count only: "Add 2 notes, edit 1"
        String noun = (allNotes ? " note" : " file") + (firstCount == 1 ? "" : "s");
        parts.set(0, parts.get(0) + noun);
        String summary = String.join(", ", parts);
        summary = Character.toUpperCase(summary.charAt(0)) + summary.substring(1);

        StringBuilder message = new StringBuilder(summary).append("\n");
        for (String line : lines) {
            message.append('\n').append(line);
        }
        return message.append('\n').toString();
    }

    /** @return the count of the first part: firstCount, or count for the first one */
    private static int addPart(List<String> parts, String verb, int count, int firstCount) {
        if (count == 0) {
            return firstCount;
        }
        parts.add(verb + " " + count);
        return parts.size() == 1 ? count : firstCount;
    }

    /**
     * Stages the given root files and commits them, as the app's author.
     *
     * @param added   files to add or update in the index
     * @param removed files to remove from the index
     */
    private RevCommit commitFiles(String message, List<String> added, List<String> removed)
            throws GitAPIException {
        stage(added, removed);
        PersonIdent ident = ident();
        RevCommit commit = git.commit()
                .setMessage(message)
                .setAuthor(ident)
                .setCommitter(ident)
                // a signing setting from a git config must never make commits fail
                .setSign(Boolean.FALSE)
                .call();
        LOG.info("committed " + commit.abbreviate(7).name() + ", files: " + (added.size() + removed.size()));
        return commit;
    }

    private void stage(List<String> added, List<String> removed) throws GitAPIException {
        if (!added.isEmpty()) {
            AddCommand add = git.add();
            for (String path : added) {
                add.addFilepattern(path);
            }
            add.call();
        }
        if (!removed.isEmpty()) {
            RmCommand rm = git.rm().setCached(true);
            for (String path : removed) {
                rm.addFilepattern(path);
            }
            rm.call();
        }
    }

    /**
     * When each file at the root was last edited, from the history, by file name
     * (e.g. "20261117-143052.md"). Files never committed aren't in it. The
     * history is read once, then only the commits made since the last call.
     *
     * @return a copy, safe to hand to another thread
     */
    public Map<String, Instant> lastEdits() throws IOException {
        Map<String, Instant> edits = new HashMap<>();
        for (Map.Entry<String, GitDates.FileDates> entry : dates().entrySet()) {
            edits.put(entry.getKey(), entry.getValue().lastEdit());
        }
        return edits;
    }

    /** Every date known for the root's files: when added and last edited. */
    Map<String, GitDates.FileDates> dates() throws IOException {
        dates.update(git.getRepository());
        return dates.all();
    }

    /**
     * Sets the author and committer of the next commits, e.g. after a change in
     * the settings.
     */
    public void setAuthor(String name, String email) {
        authorName = name;
        authorEmail = email;
    }

    /** The app's author, now. */
    PersonIdent ident() {
        return new PersonIdent(authorName, authorEmail,
                Date.from(clock.instant()), TimeZone.getTimeZone(clock.getZone()));
    }

    /** The JGit repository, for the tests and for reading the history. */
    Repository repository() {
        return git.getRepository();
    }

    @Override
    public void close() {
        git.close();
    }
}
