package com.operametrix.ignition.git.automation;

import com.operametrix.ignition.git.managers.GitManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * What GitHub Actions workflows a project's repository already has.
 *
 * <p>The Runner tab used to present "add the workflow" as a step everyone must do, which is wrong
 * far more often than it is right: a repository that deploys anywhere already has workflows, and
 * dropping another one in beside them produces two deployments fighting over the same project.
 * The gateway has the project's working tree on disk, so it can simply look.
 *
 * <p>A workflow counts as already wired up when its text mentions one of this module's runner
 * routes. That is a deliberately shallow test — it matches any gateway's address, not only this
 * one — because the useful question is "does something here already deploy through the module",
 * and a workflow pointed at a sibling gateway still answers yes.
 */
public final class WorkflowScan {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowScan.class);

    /** Both runner routes share this prefix, so one needle covers release and repo updates. */
    private static final String ROUTE_MARKER = "/data/git-config/runner-";

    /** A workflow file that GitHub would actually run. */
    public record Workflow(String path, boolean callsGateway) {}

    /**
     * @param detectable false when there is no working tree to look in, which is the normal case
     *                   for a release-mode project that is not a repository on this gateway. The
     *                   page must say "cannot be checked" rather than imply the repository is empty.
     */
    public record Result(boolean detectable, List<Workflow> workflows) {}

    private WorkflowScan() {
    }

    public static Result scan(String project) {
        if (project == null || project.isBlank()) {
            return new Result(false, List.of());
        }
        Path dir;
        try {
            dir = GitManager.getProjectFolderPath(project).resolve(".github").resolve("workflows");
        } catch (Exception e) {
            logger.debug("No project folder for {} while scanning for workflows.", project, e);
            return new Result(false, List.of());
        }
        if (!Files.isDirectory(dir)) {
            // A project folder with no .github IS a real answer: no workflows. Only a missing
            // project folder means we could not look.
            boolean projectExists = Files.isDirectory(dir.getParent().getParent());
            return new Result(projectExists, List.of());
        }
        List<Workflow> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> yaml = files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".yml") || n.endsWith(".yaml");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path p : yaml) {
                boolean calls = false;
                try {
                    calls = Files.readString(p, StandardCharsets.UTF_8).contains(ROUTE_MARKER);
                } catch (Exception unreadable) {
                    // A workflow we cannot read still exists, and that is the part that matters.
                    logger.debug("Could not read {}.", p, unreadable);
                }
                found.add(new Workflow(".github/workflows/" + p.getFileName(), calls));
            }
        } catch (Exception e) {
            logger.debug("Could not list workflows for {}.", project, e);
            return new Result(false, List.of());
        }
        return new Result(true, found);
    }
}
