/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.nbfx.ide.vcs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.netbeans.modules.nbfx.ide.vcs.GitHistory.Commit;
import org.openide.util.NbBundle;

/**
 * What is done with a commit of the history, through the {@code git} command line: checked out,
 * branched from, tagged, applied again where the repository is now, undone, and written out as a
 * patch. Each runs on the calling thread and answers with what git said.
 * <p>
 * A commit that merges others is applied again and undone as what it brought to the first of the
 * lines it joined, which is how the history lists its files.
 *
 * @since 1.5
 */
final class GitCommits {

    /** What git says is matched, and shown, as said, not translated. */
    private static final Map<String, String> ENVIRONMENT = Map.of("LC_ALL", "C");

    private GitCommits() {
    }

    /** Checks {@code commit} out, on no branch. */
    static GitClient.Result checkout(Path root, Commit commit) {
        return git(root, "checkout", "--detach", commit.id());
    }

    /**
     * Creates the branch {@code name} at {@code commit}.
     *
     * @param checkout whether the new branch is checked out as well
     */
    static GitClient.Result branch(Path root, String name, Commit commit, boolean checkout) {
        return checkout ? git(root, "checkout", "-b", name, commit.id()) : git(root, "branch", "--", name, commit.id());
    }

    /**
     * Tags {@code commit} as {@code name}.
     *
     * @param message what the tag says; with none the tag is a plain name of the commit
     */
    static GitClient.Result tag(Path root, String name, Commit commit, String message) {
        if (message == null || message.isBlank()) {
            return git(root, "tag", "--", name, commit.id());
        }
        return git(root, "tag", "-a", "-m", message.strip(), "--", name, commit.id());
    }

    /** Applies the changes of {@code commit} to what is checked out, as a new commit. */
    static GitClient.Result cherryPick(Path root, Commit commit) {
        return git(root, withMainline("cherry-pick", commit));
    }

    /** Undoes the changes of {@code commit}, with a new commit that says so. */
    static GitClient.Result revert(Path root, Commit commit) {
        List<String> args = new ArrayList<>(List.of(withMainline("revert", commit)));
        args.add(1, "--no-edit");
        return git(root, args.toArray(String[]::new));
    }

    /**
     * {@code commit} as a patch to mail or to apply elsewhere - its author, date and message, and
     * its changes - as the output of the result. A commit that merges others has no changes of
     * its own, and git would write the commits it merged in its place: that is refused, and said.
     */
    static GitClient.Result patch(Path root, Commit commit) {
        if (commit.isMerge()) {
            return new GitClient.Result(1, NbBundle.getMessage(GitCommits.class, "Git.commit.export.merge"));
        }
        return git(root, "format-patch", "-1", "--stdout", commit.id());
    }

    /** {@code command} of {@code commit}, told for a merge which of its lines it is taken against. */
    private static String[] withMainline(String command, Commit commit) {
        return commit.isMerge() ? new String[] {command, "-m", "1", commit.id()} : new String[] {command, commit.id()};
    }

    private static GitClient.Result git(Path root, String... args) {
        return GitClient.result(root, ENVIRONMENT, args);
    }
}
