/*
 * Copyright 2014-2018 Aleksandr Mashchenko.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amashchenko.maven.plugin.gitflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.components.interactivity.PrompterException;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;

/**
 * The git flow hotfix finish mojo.
 *
 */
@Mojo(name = "hotfix-finish", aggregator = true)
public class GitFlowHotfixFinishMojo extends AbstractGitFlowMojo {

    /** Whether to skip tagging the hotfix in Git. */
    @Parameter(property = "skipTag", defaultValue = "false")
    private boolean skipTag = false;

    /** Whether to keep hotfix branch after finish. */
    @Parameter(property = "keepBranch", defaultValue = "false")
    private boolean keepBranch = false;

    /**
     * Whether to skip calling Maven test goal before merging the branch.
     *
     * @since 1.0.5
     */
    @Parameter(property = "skipTestProject", defaultValue = "false")
    private boolean skipTestProject = false;

    /**
     * Whether to push to the remote.
     *
     * @since 1.3.0
     */
    @Parameter(property = "pushRemote", defaultValue = "true")
    private boolean pushRemote;

    /**
     * Maven goals to execute in the hotfix branch before merging into the
     * production or support branch.
     *
     * @since 1.8.0
     */
    @Parameter(property = "preHotfixGoals")
    private String preHotfixGoals;

    /**
     * Maven goals to execute in the release or support branch after the hotfix.
     *
     * @since 1.8.0
     */
    @Parameter(property = "postHotfixGoals")
    private String postHotfixGoals;

    /**
     * Hotfix version to use in non-interactive mode.
     *
     * @since 1.9.0
     */
    @Parameter(property = "hotfixVersion")
    private String hotfixVersion;

    /**
     * Whether to make a GPG-signed tag.
     *
     * @since 1.9.0
     */
    @Parameter(property = "gpgSignTag", defaultValue = "false")
    private boolean gpgSignTag = false;

    /**
     * Whether this is use snapshot in hotfix.
     *
     * @since 1.10.0
     */
    @Parameter(property = "useSnapshotInHotfix", defaultValue = "false")
    protected boolean useSnapshotInHotfix;

    /**
     * Whether we use the support has a development branch
     *
     * @since 1.10.1
     */
    @Parameter(property = "useSupportHasDevelop", defaultValue = "false")
    protected boolean useSupportHasDevelop;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        validateConfiguration(preHotfixGoals, postHotfixGoals);

        try {
            // check uncommitted changes
            checkUncommittedChanges();

            String hotfixBranchName = null;
            if (settings.isInteractiveMode()) {
                hotfixBranchName = promptBranchName();
            } else if (StringUtils.isNotBlank(hotfixVersion)) {
                final String branch = gitFlowConfig.getHotfixBranchPrefix()
                        + hotfixVersion;
                if (!gitCheckBranchExists(branch)) {
                    throw new MojoFailureException(
                            "Hotfix branch with name '" + branch
                                    + "' doesn't exist. Cannot finish hotfix.");
                }
                hotfixBranchName = branch;
            }

            if (StringUtils.isBlank(hotfixBranchName)) {
                throw new MojoFailureException(
                        "Hotfix branch name to finish is blank.");
            }

            // support branch hotfix
            String supportBranchName = null;
            boolean supportHotfix = hotfixBranchName
                    .startsWith(gitFlowConfig.getHotfixBranchPrefix()
                            + gitFlowConfig.getSupportBranchPrefix());
            // get support branch name w/o version part
            if (supportHotfix) {
                supportBranchName = hotfixBranchName.substring(
                        gitFlowConfig.getHotfixBranchPrefix().length());
                supportBranchName = supportBranchName.substring(0,
                        supportBranchName.lastIndexOf('/'));
            }

            // fetch and check remote
            if (fetchRemote) {
                gitFetchRemoteAndCompare(hotfixBranchName);

                if (supportBranchName != null) {
                    gitFetchRemoteAndCompare(supportBranchName);
                } else {
                    if (notSameProdDevName()) {
                        gitFetchRemoteAndCreate(gitFlowConfig.getDevelopmentBranch());
                        gitFetchRemoteAndCompare(gitFlowConfig.getDevelopmentBranch());
                    }
                    gitFetchRemoteAndCreate(gitFlowConfig.getProductionBranch());
                    gitFetchRemoteAndCompare(gitFlowConfig.getProductionBranch());
                }
            }

            if (!skipTestProject) {
                // git checkout hotfix/...
                gitCheckout(hotfixBranchName);

                // mvn clean test
                mvnCleanTest();
            }

            // maven goals before merge
            if (StringUtils.isNotBlank(preHotfixGoals)) {
                gitCheckout(hotfixBranchName);

                mvnRun(preHotfixGoals);
            }

            String currentHotfixVersion = getCurrentProjectVersion();
            String commitVersion = currentHotfixVersion;

            if (useSnapshotInHotfix && ArtifactUtils.isSnapshot(currentHotfixVersion)) {

                commitVersion = currentHotfixVersion.replace("-" + Artifact.SNAPSHOT_VERSION, "");

                mvnSetVersions(commitVersion);

                Map<String, String> properties = new HashMap<String, String>();
                properties.put("version", commitVersion);

                gitCommit(commitMessages.getHotfixStartMessage(), properties);
            }

            String nextSnapshotVersion = null;

            if (supportBranchName != null) {

                gitCheckout(supportBranchName);

                // if a support branch exists we can have the situation where
                // the support is being used as a concurrent development branch,
                // for a different client for example, in parallel with the
                // development branch. In this case the version is normally a
                // SNAPSHOT and each time we do releases we want to increment
                // the SNAPSHOT version. -> useSupportHasDevelop
                if (useSupportHasDevelop) {

                    // set hotfix version into support to avoid merge
                    // conflicts
                    mvnSetVersions(commitVersion);

                    // commit the temporary version
                    gitCommit("update to hotfix version to avoid merge conflits");

                    // create version info object for the hotfix version
                    GitFlowVersionInfo hotfixVersionInfo = new GitFlowVersionInfo(commitVersion);

                    String supportVersion = getCurrentProjectVersion();

                    if (ArtifactUtils.isSnapshot(supportVersion)) {
                        supportVersion = supportVersion.replace("-" + Artifact.SNAPSHOT_VERSION, "");
                    }

                    // create version info object for the support version
                    GitFlowVersionInfo supportVersionInfo = new GitFlowVersionInfo(supportVersion);

                    // if the support version is superior to the hotfix one
                    // we want to keep that version in support
                    if (supportVersionInfo.compareTo(hotfixVersionInfo) > 0) {
                        nextSnapshotVersion = supportVersionInfo.getSnapshotVersionString();
                    }
                    else {
                        nextSnapshotVersion = hotfixVersionInfo.nextSnapshotVersion();
                    }

                }

            } else {

                // git checkout master
                gitCheckout(gitFlowConfig.getProductionBranch());
            }

            // git merge --no-ff hotfix/...
            gitMergeNoff(hotfixBranchName);

            if (!skipTag) {

                String tagVersion = commitVersion;

                if ((tychoBuild || useSnapshotInHotfix) && ArtifactUtils.isSnapshot(tagVersion)) {
                    tagVersion = tagVersion
                            .replace("-" + Artifact.SNAPSHOT_VERSION, "");
                }

                Map<String, String> properties = new HashMap<String, String>();
                properties.put("version", tagVersion);

                // git tag -a ...
                gitTag(gitFlowConfig.getVersionTagPrefix() + tagVersion,
                        commitMessages.getTagHotfixMessage(), gpgSignTag, properties);
            }

            // maven goals after merge
            if (StringUtils.isNotBlank(postHotfixGoals)) {
                mvnRun(postHotfixGoals);
            }

            if (supportBranchName != null && useSupportHasDevelop) {

                mvnSetVersions(nextSnapshotVersion);

                Map<String, String> properties = new HashMap<String, String>();
                properties.put("version", nextSnapshotVersion);

                gitCommit(commitMessages.getSuportHotfixFinishMessage(),
                        properties);
            }

            // check whether release branch exists
            // git for-each-ref --count=1 --format="%(refname:short)"
            // refs/heads/release/*
            final String releaseBranch = gitFindBranches(
                    gitFlowConfig.getReleaseBranchPrefix(), true);

            if (supportBranchName == null) {

                // if release branch exists merge hotfix changes into it
                if (StringUtils.isNotBlank(releaseBranch)) {
                    // git checkout release
                    gitCheckout(releaseBranch);
                    // git merge --no-ff hotfix/...
                    gitMergeNoff(hotfixBranchName);
                } else {

                    // create version info object for the hotfix version
                    GitFlowVersionInfo hotfixVersionInfo = new GitFlowVersionInfo(commitVersion);

                    // for now, the next snapshot version to set in develop
                    // branch is the the hotfix incremented by 1
                    nextSnapshotVersion = hotfixVersionInfo.nextSnapshotVersion();

                    if (notSameProdDevName()) {

                        // git checkout develop
                        gitCheckout(gitFlowConfig.getDevelopmentBranch());

                        // create version info object for the develop version
                        GitFlowVersionInfo developVersionInfo = new GitFlowVersionInfo(getCurrentProjectVersion());

                        // set hotfix version into develop to avoid merge
                        // conflict
                        mvnSetVersions(commitVersion);

                        // commit the temporary version
                        gitCommit("update to hotfix version to avoid merge conflits");

                        // git merge --no-ff hotfix/...
                        gitMergeNoff(hotfixBranchName);

                        // if the develop version is superior to the hotfix one
                        // we want to keep it
                        if (developVersionInfo.compareTo(hotfixVersionInfo) > 0) {
                            nextSnapshotVersion = developVersionInfo.getSnapshotVersionString();
                        }
                    }

                    if (StringUtils.isBlank(nextSnapshotVersion)) {
                        throw new MojoFailureException(
                                "Next snapshot version is blank.");
                    }

                    // mvn versions:set -DnewVersion=...
                    // -DgenerateBackupPoms=false
                    mvnSetVersions(nextSnapshotVersion);

                    Map<String, String> properties = new HashMap<String, String>();
                    properties.put("version", nextSnapshotVersion);

                    // git commit -a -m updating for next development version
                    gitCommit(commitMessages.getHotfixFinishMessage(),
                            properties);
                }
            }

            if (installProject) {
                // mvn clean install
                mvnCleanInstall();
            }

            if (pushRemote) {
                if (supportBranchName != null) {
                    gitPush(supportBranchName, !skipTag);
                } else {
                    gitPush(gitFlowConfig.getProductionBranch(), !skipTag);

                    // if no release branch
                    if (StringUtils.isBlank(releaseBranch)
                            && notSameProdDevName()) {
                        gitPush(gitFlowConfig.getDevelopmentBranch(), !skipTag);
                    }
                }

                if (!keepBranch) {
                    gitPushDelete(hotfixBranchName);
                }
            }

            if (!keepBranch) {
                // git branch -d hotfix/...
                gitBranchDelete(hotfixBranchName);
            }
        } catch (Exception e) {
            throw new MojoFailureException("hotfix-finish", e);
        }
    }

    private String promptBranchName() throws MojoFailureException, CommandLineException {
        // git for-each-ref --format='%(refname:short)' refs/heads/hotfix/*
        String hotfixBranches = gitFindBranches(gitFlowConfig.getHotfixBranchPrefix(), false);

        // find hotfix support branches
        if (!gitFlowConfig.getHotfixBranchPrefix().endsWith("/")) {
            String supportHotfixBranches = gitFindBranches(gitFlowConfig.getHotfixBranchPrefix() + "*/*", false);
            hotfixBranches = hotfixBranches + supportHotfixBranches;
        }

        if (StringUtils.isBlank(hotfixBranches)) {
            throw new MojoFailureException("There are no hotfix branches.");
        }

        String[] branches = hotfixBranches.split("\\r?\\n");

        List<String> numberedList = new ArrayList<String>();
        StringBuilder str = new StringBuilder("Hotfix branches:").append(LS);
        for (int i = 0; i < branches.length; i++) {
            str.append(i + 1 + ". " + branches[i] + LS);
            numberedList.add(String.valueOf(i + 1));
        }
        str.append("Choose hotfix branch to finish");

        String hotfixNumber = null;
        try {
            while (StringUtils.isBlank(hotfixNumber)) {
                hotfixNumber = prompter.prompt(str.toString(), numberedList);
            }
        } catch (PrompterException e) {
            throw new MojoFailureException("hotfix-finish", e);
        }

        String hotfixBranchName = null;
        if (hotfixNumber != null) {
            int num = Integer.parseInt(hotfixNumber);
            hotfixBranchName = branches[num - 1];
        }

        return hotfixBranchName;
    }
}
