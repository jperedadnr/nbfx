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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.netbeans.api.nbfx.dialogs.ErrorReporter;
import org.netbeans.api.nbfx.status.StatusMessageService;
import org.netbeans.modules.nbfx.ide.vcs.GitBrowserActions.Form;
import org.netbeans.modules.nbfx.ide.vcs.GitHistory.Commit;
import org.netbeans.modules.nbfx.ide.vcs.GitHistoryPane.CommitAction;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;

/**
 * The actions of a commit in the History view, as the links NetBeans has under one: Checkout, Tag
 * Commit, Cherry Pick, Export Commit and Revert Commit. Each asks what it needs in a dialog, runs
 * git off the FX thread, says what git refused, and has the view search again.
 *
 * @since 1.5
 */
final class GitCommitActions {

    private static final RequestProcessor RP = new RequestProcessor("nbfx-git-commit-actions", 1);

    private final Path root;
    private final Runnable done;

    /**
     * @param root the root of the working tree of the commits
     * @param done run on the FX thread after an action that may have changed the repository
     */
    GitCommitActions(Path root, Runnable done) {
        this.root = Objects.requireNonNull(root);
        this.done = Objects.requireNonNull(done);
    }

    /** The actions of {@code commit}, in the order of their links. */
    List<CommitAction> actions(Commit commit) {
        return List.of(
                new CommitAction(NbBundle.getMessage(GitCommitActions.class, "Git.commit.checkout", commit.shortId()),
                        () -> checkout(commit)),
                new CommitAction(message("Git.commit.tag"), () -> tag(commit)),
                new CommitAction(message("Git.commit.cherryPick"), () -> cherryPick(commit)),
                new CommitAction(message("Git.commit.export"), () -> export(commit)),
                new CommitAction(message("Git.commit.revert"), () -> revert(commit)));
    }

    /** Checkout: checks the commit out on no branch, or as a new branch if asked to. */
    void checkout(Commit commit) {
        CheckBox asNew = new CheckBox(message("Git.branch.checkout.asNew"));
        TextField name = GitBrowserActions.nameField("");
        name.disableProperty().bind(asNew.selectedProperty().not());
        Form form = new Form("Git.branch.checkout.title", null, null, null);
        form.add("Git.revision.id", Form.shown(new TextField(told(commit))));
        form.add(asNew);
        form.add("Git.branch.name", name);
        // A new branch needs a name.
        form.invalidWhen(Bindings.createBooleanBinding(() -> asNew.isSelected() && name.getText().isBlank(),
                asNew.selectedProperty(), name.textProperty()));
        if (form.confirmed(asNew)) {
            String branch = asNew.isSelected() ? name.getText().strip() : null;
            run("Git.branch.checkout.title", true, () -> branch == null ? GitCommits.checkout(root, commit)
                    : GitCommits.branch(root, branch, commit, true));
        }
    }

    /** Tag Commit: gives the commit a name, with a message if one is typed. */
    void tag(Commit commit) {
        TextField name = GitBrowserActions.nameField("");
        TextArea text = new TextArea();
        text.setPrefRowCount(4);
        text.setPrefColumnCount(28);
        text.setWrapText(true);
        Form form = new Form("Git.branch.tag.title", null, null, null);
        form.add("Git.revision.id", Form.shown(new TextField(told(commit))));
        form.add("Git.tag.name", name);
        form.add("Git.tag.message", text);
        form.invalidWhen(name.textProperty().map(String::isBlank));
        if (form.confirmed(name)) {
            String created = name.getText().strip();
            String said = text.getText();
            run("Git.branch.tag.title", false, () -> GitCommits.tag(root, created, commit, said));
        }
    }

    /** Cherry Pick: applies the changes of the commit where the repository is now, once agreed to. */
    void cherryPick(Commit commit) {
        if (GitBrowserActions.confirmed("Git.commit.cherryPick.title", NbBundle.getMessage(GitCommitActions.class,
                "Git.commit.cherryPick.question", commit.shortId(), commit.summary()))) {
            run("Git.commit.cherryPick.title", true, () -> GitCommits.cherryPick(root, commit));
        }
    }

    /** Revert Commit: undoes the changes of the commit with a new one, once agreed to. */
    void revert(Commit commit) {
        if (GitBrowserActions.confirmed("Git.commit.revert.title", NbBundle.getMessage(GitCommitActions.class,
                "Git.commit.revert.question", commit.shortId(), commit.summary()))) {
            run("Git.commit.revert.title", true, () -> GitCommits.revert(root, commit));
        }
    }

    /** Export Commit: writes the commit as a patch to the file chosen. */
    void export(Commit commit) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(message("Git.commit.export.title"));
        chooser.setInitialFileName(commit.shortId() + ".patch");
        Window owner = Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(null);
        File file = chooser.showSaveDialog(owner);
        if (file != null) {
            export(commit, file.toPath());
        }
    }

    /** Writes {@code commit} as a patch to {@code file}, off the FX thread, and says so. */
    void export(Commit commit, Path file) {
        RP.post(() -> {
            GitClient.Result patch = GitCommits.patch(root, commit);
            String failed = null;
            if (patch.exitCode() != 0) {
                failed = patch.output().strip();
            } else {
                try {
                    Files.writeString(file, patch.output(), StandardCharsets.UTF_8);
                    FileUtil.refreshFor(file.toFile());
                } catch (IOException ex) {
                    failed = String.valueOf(ex.getMessage());
                }
            }
            String said = failed;
            Platform.runLater(() -> {
                if (said != null) {
                    ErrorReporter.report(message("Git.commit.export.title"), message("Git.error"), said, null);
                    return;
                }
                StatusMessageService status = Lookup.getDefault().lookup(StatusMessageService.class);
                if (status != null) {
                    status.setMessage(NbBundle.getMessage(GitCommitActions.class, "Git.commit.exported", file));
                }
            });
        });
    }

    /**
     * Runs {@code command} off the FX thread, then says what failed, if it did, and has the view
     * search again.
     *
     * @param changesFiles whether the command changes the working tree, whose files are then looked at again
     */
    void run(String titleKey, boolean changesFiles, Supplier<GitClient.Result> command) {
        RP.post(() -> {
            GitClient.Result result = command.get();
            if (changesFiles) {
                // Changed behind the filesystem's back, also by a cherry-pick that stopped at a conflict.
                FileUtil.refreshFor(root.toFile());
            }
            Platform.runLater(() -> {
                if (result.exitCode() != 0) {
                    ErrorReporter.report(message(titleKey), message("Git.error"), result.output().strip(), null);
                }
                done.run();
            });
        });
    }

    /** {@code commit} as a dialog names it: the start of its id and the first line of its message. */
    private static String told(Commit commit) {
        return NbBundle.getMessage(GitCommitActions.class, "Git.commit.told", commit.shortId(), commit.summary());
    }

    private static String message(String key) {
        return NbBundle.getMessage(GitCommitActions.class, key);
    }
}
