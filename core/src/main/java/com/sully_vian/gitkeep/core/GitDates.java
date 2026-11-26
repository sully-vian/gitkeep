package com.sully_vian.gitkeep.core;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jspecify.annotations.Nullable;

/**
 * The dates of the files at the root of a repository, read from its history,
 * since the files themselves hold none: when each file was added, and when it
 * was last edited.
 *
 * <p>
 * Cached: the history is walked once, then only the commits made since the
 * last {@link #update}.
 *
 * <p>
 * Not thread-safe: use it from one thread only.
 */
final class GitDates {

    private static final Logger LOG = Logger.getLogger(GitDates.class.getName());

    /**
     * The dates of one file, as it is in the last commit.
     *
     * @param added    when the file appeared: the author time of the commit that
     *                 added it ({@code git log --diff-filter=A --format=%aI}). A
     *                 file deleted then added again counts from the new add.
     * @param lastEdit when the file was last changed: the commit time of the last
     *                 commit touching it ({@code git log -1 --format=%cI})
     */
    record FileDates(Instant added, Instant lastEdit) {
    }

    private final Map<String, FileDates> dates = new HashMap<>();

    /** The commit the dates are up to, null before the first update. */
    private @Nullable ObjectId upTo;

    /**
     * Reads the commits made since the last update, oldest first, so that later
     * changes win. Each commit is compared with its first parent only: a merge's
     * changes count at the merge's own time.
     */
    void update(Repository repository) throws IOException {
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null || head.equals(upTo)) {
            return;
        }
        int commits = 0;
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(head));
            if (upTo != null) {
                walk.markUninteresting(walk.parseCommit(upTo));
            }
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.REVERSE, true);
            for (RevCommit commit : walk) {
                read(repository, walk, commit);
                commits++;
            }
        }
        upTo = head;
        LOG.info("dates read, new commits: " + commits);
    }

    /** The dates of the root's files in the last commit, by file name: a copy. */
    Map<String, FileDates> all() {
        return new HashMap<>(dates);
    }

    private void read(Repository repository, RevWalk revWalk, RevCommit commit) throws IOException {
        Instant authored = commit.getAuthorIdent().getWhen().toInstant();
        Instant committed = commit.getCommitterIdent().getWhen().toInstant();
        try (TreeWalk walk = new TreeWalk(repository)) {
            if (commit.getParentCount() == 0) {
                walk.addTree(new EmptyTreeIterator());
            } else {
                walk.addTree(revWalk.parseCommit(commit.getParent(0)).getTree());
            }
            walk.addTree(commit.getTree());
            // not recursive: the root's entries only; subfolders are skipped below
            walk.setRecursive(false);
            walk.setFilter(TreeFilter.ANY_DIFF);
            while (walk.next()) {
                String path = walk.getPathString();
                FileMode before = walk.getFileMode(0);
                FileMode after = walk.getFileMode(1);
                if (after == FileMode.MISSING) {
                    dates.remove(path);
                } else if (after == FileMode.TREE) {
                    // a folder: not a note
                    dates.remove(path);
                } else if (before == FileMode.MISSING || before == FileMode.TREE) {
                    dates.put(path, new FileDates(authored, committed));
                } else {
                    FileDates old = dates.get(path);
                    dates.put(path, new FileDates(old == null ? authored : old.added(), committed));
                }
            }
        }
    }
}
