package com.sully_vian.gitkeep.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.logging.Logger;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * The git repository of the notes folder: the folder is its work tree, with the
 * repository in ".git" inside it (hidden, so {@link NoteStore} never lists it).
 *
 * <p>
 * Blocking file I/O, and not thread-safe: use it from one thread only.
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
    private final String authorName;
    private final String authorEmail;
    private final Clock clock;

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

    /** @return the names of the files written */
    private List<String> writeMissingSetupFiles() throws IOException {
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
     * Stages the given root files and commits them, as the app's author.
     *
     * @param added   files to add or update in the index
     * @param removed files to remove from the index
     */
    private RevCommit commitFiles(String message, List<String> added, List<String> removed)
            throws GitAPIException {
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
        PersonIdent ident = new PersonIdent(authorName, authorEmail,
                Date.from(clock.instant()), TimeZone.getTimeZone(clock.getZone()));
        RevCommit commit = git.commit()
                .setMessage(message)
                .setAuthor(ident)
                .setCommitter(ident)
                // a signing setting from a git config must never make commits fail
                .setSign(Boolean.FALSE)
                .call();
        LOG.info("committed " + commit.abbreviate(7).name() + ": "
                + (added.size() + removed.size()) + " files");
        return commit;
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
