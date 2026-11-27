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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.netbeans.modules.nbfx.ide.vcs.GitHistory.Commit;
import org.netbeans.modules.nbfx.ide.vcs.GitHistory.Criteria;
import org.netbeans.modules.nbfx.ide.vcs.GitHistoryPane.CommitAction;
import org.netbeans.modules.nbfx.test.fx.JavaFxTestSupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Does to the commits of a real repository what the History view offers, through the {@code git}
 * command line. Skipped where git is not installed.
 * <p>
 * The repository has, the newest first: {@code merge} (of the branch {@code side}, which added
 * {@code side.txt}), {@code second} (which changed {@code a.txt}), {@code side} and {@code first}.
 */
public class GitCommitsTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private Path root;
    private Commit merge;
    private Commit second;
    private Commit first;

    @Before
    public void createRepository() throws Exception {
        Assume.assumeTrue("git is not installed", gitAvailable());
        root = temp.newFolder("repo").toPath();
        git("init", "--quiet", "--initial-branch=main");
        // The test must not depend on the machine's configuration.
        git("config", "user.name", "nbfx test");
        git("config", "user.email", "test@example.invalid");
        git("config", "commit.gpgsign", "false");
        git("config", "core.autocrlf", "false");
        write("a.txt", "one\n");
        git("add", "-A");
        git("commit", "--quiet", "-m", "first");
        git("checkout", "--quiet", "-b", "side");
        write("side.txt", "side\n");
        git("add", "-A");
        git("commit", "--quiet", "-m", "side");
        git("checkout", "--quiet", "main");
        write("a.txt", "one\ntwo\n");
        git("commit", "--quiet", "-am", "second");
        git("merge", "--quiet", "--no-ff", "side", "-m", "merge");
        List<Commit> commits = GitLog.read(root, Criteria.of(List.of())).commits();
        merge = commits.get(0);
        second = commits.stream().filter(commit -> commit.summary().equals("second")).findFirst().orElseThrow();
        first = commits.stream().filter(commit -> commit.summary().equals("first")).findFirst().orElseThrow();
    }

    @After
    public void hideWindows() throws InterruptedException {
        JavaFxTestSupport.hideWindows();
    }

    @Test
    public void aCommitIsCheckedOutOnNoBranchOrAsANewOne() throws Exception {
        assertEquals(0, GitCommits.checkout(root, first).exitCode());
        assertEquals(first.id(), gitOutput("rev-parse", "HEAD"));
        // On no branch: git names none.
        assertTrue(gitFails("symbolic-ref", "--quiet", "HEAD"));
        assertEquals("one\n", Files.readString(root.resolve("a.txt")));

        assertEquals(0, GitCommits.branch(root, "from-second", second, true).exitCode());
        assertEquals("refs/heads/from-second", gitOutput("symbolic-ref", "HEAD"));
        assertEquals(second.id(), gitOutput("rev-parse", "HEAD"));

        // A branch made and not checked out.
        assertEquals(0, GitCommits.branch(root, "kept", first, false).exitCode());
        assertEquals(first.id(), gitOutput("rev-parse", "refs/heads/kept"));
        assertEquals("refs/heads/from-second", gitOutput("symbolic-ref", "HEAD"));
        // What git refuses is said in its words.
        GitClient.Result again = GitCommits.branch(root, "kept", first, false);
        assertNotEquals(0, again.exitCode());
        assertTrue(again.output(), again.output().contains("already exists"));
    }

    @Test
    public void aCommitIsTaggedWithOrWithoutAMessage() throws Exception {
        assertEquals(0, GitCommits.tag(root, "plain", first, " ").exitCode());
        assertEquals(0, GitCommits.tag(root, "said", second, "The second one\n").exitCode());

        assertEquals(first.id(), gitOutput("rev-parse", "refs/tags/plain^{commit}"));
        assertEquals("commit", gitOutput("cat-file", "-t", "refs/tags/plain"));
        assertEquals(second.id(), gitOutput("rev-parse", "refs/tags/said^{commit}"));
        assertEquals("tag", gitOutput("cat-file", "-t", "refs/tags/said"));
        assertEquals("The second one", gitOutput("tag", "-l", "--format=%(contents:subject)", "said"));
    }

    @Test
    public void aCommitIsAppliedAgainElsewhereAndUndone() throws Exception {
        // Where the change of the second commit is not: the branch made from the first.
        git("checkout", "--quiet", "-b", "elsewhere", first.id());
        assertEquals("one\n", Files.readString(root.resolve("a.txt")));

        assertEquals(0, GitCommits.cherryPick(root, second).exitCode());
        assertEquals("one\ntwo\n", Files.readString(root.resolve("a.txt")));
        assertEquals("second", gitOutput("log", "-1", "--format=%s"));
        assertEquals("elsewhere", gitOutput("rev-parse", "--abbrev-ref", "HEAD"));

        assertEquals(0, GitCommits.revert(root, second).exitCode());
        assertEquals("one\n", Files.readString(root.resolve("a.txt")));
        assertEquals("Revert \"second\"", gitOutput("log", "-1", "--format=%s"));
    }

    @Test
    public void aMergeIsAppliedAndUndoneAsWhatItBroughtToItsBranch() throws Exception {
        // Undone on its own branch: the file the merged branch added goes.
        assertEquals(0, GitCommits.revert(root, merge).exitCode());
        assertFalse(Files.exists(root.resolve("side.txt")));
        assertEquals("one\ntwo\n", Files.readString(root.resolve("a.txt")));

        // And applied where that file is not.
        git("checkout", "--quiet", "-b", "elsewhere", first.id());
        assertEquals(0, GitCommits.cherryPick(root, merge).exitCode());
        assertEquals("side\n", Files.readString(root.resolve("side.txt")));
        assertEquals("one\n", Files.readString(root.resolve("a.txt")));
    }

    @Test
    public void aCommitIsWrittenAsAPatchThatAppliesElsewhere() throws Exception {
        GitClient.Result patch = GitCommits.patch(root, second);

        assertEquals(0, patch.exitCode());
        assertTrue(patch.output(), patch.output().startsWith("From " + second.id()));
        assertTrue(patch.output().contains("Subject: [PATCH] second"));
        assertTrue(patch.output().contains("+two"));
        // A commit that merges others has no changes of its own: refused, not the commits it merged.
        GitClient.Result ofMerge = GitCommits.patch(root, merge);
        assertNotEquals(0, ofMerge.exitCode());
        assertFalse(ofMerge.output(), ofMerge.output().contains("Subject:"));

        // The action says it is done on the FX thread, which must be there.
        JavaFxTestSupport.onFx(() -> { });
        Path file = temp.newFolder("out").toPath().resolve("second.patch");
        new GitCommitActions(root, () -> { }).export(second, file);
        for (int i = 0; i < 200 && !Files.exists(file); i++) {
            Thread.sleep(50);
        }
        assertEquals(patch.output(), Files.readString(file));
        git("checkout", "--quiet", "-b", "elsewhere", first.id());
        git("am", "--quiet", file.toString());
        assertEquals("one\ntwo\n", Files.readString(root.resolve("a.txt")));
    }

    @Test
    public void theActionsOfACommitAreThoseOfItsLinks() {
        List<CommitAction> actions = new GitCommitActions(root, () -> { }).actions(second);

        assertEquals(List.of("Checkout " + second.shortId(), "Tag Commit", "Cherry Pick...", "Export Commit...",
                "Revert Commit..."), actions.stream().map(CommitAction::text).toList());
    }

    @Test
    public void whatAnActionDidIsFollowedByTheViewSearchingAgain() throws Exception {
        JavaFxTestSupport.onFx(() -> { });
        int[] done = new int[1];
        GitCommitActions actions = new GitCommitActions(root, () -> done[0]++);

        actions.run("Git.branch.tag.title", false, () -> GitCommits.tag(root, "v1", first, null));
        for (int i = 0; i < 200 && JavaFxTestSupport.onFx(() -> done[0]) == 0; i++) {
            Thread.sleep(50);
        }

        assertEquals(1, (int) JavaFxTestSupport.onFx(() -> done[0]));
        assertEquals(first.id(), gitOutput("rev-parse", "refs/tags/v1^{commit}"));
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
    }

    private void git(String... args) throws Exception {
        GitClient.Result result = run(args);
        assertEquals(String.join(" ", args) + "\n" + result.output(), 0, result.exitCode());
    }

    private boolean gitFails(String... args) throws Exception {
        return run(args).exitCode() != 0;
    }

    private String gitOutput(String... args) throws Exception {
        return run(args).output().strip();
    }

    private GitClient.Result run(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitClient.Result(process.waitFor(), output);
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
