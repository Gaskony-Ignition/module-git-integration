package com.operametrix.ignition.git.automation;

import com.operametrix.ignition.git.records.GitRunnerRecord;

import java.util.Locale;

/**
 * Generates the copy-and-paste setup for a GitHub Actions self-hosted runner.
 *
 * <p>None of this is clever — it is the standard runner setup with this gateway's values already
 * substituted in. The reason it exists is that the four values involved (repository URL, runner
 * labels, gateway address, trigger token) have to agree across two systems and three files, and
 * getting one of them subtly wrong produces a workflow that queues forever with no error.
 *
 * <p>The gateway deliberately does not install or supervise the runner itself. A runner executes
 * whatever the workflow file says; hosting one from inside the module would put arbitrary
 * repository-supplied shell next to the project store, under the gateway's own identity.
 */
public final class RunnerSetup {

    /**
     * The runner release the generated commands download. GitHub's own runner page always shows
     * the current version, and an older runner self-updates on first connection, so this going
     * stale costs a minute rather than a failure.
     */
    private static final String RUNNER_VERSION = "2.337.0";

    /**
     * Where the workflow has to live. GitHub reads workflows only from the repository root, and
     * for a project repository the root IS the project folder — so the file lands beside the
     * project's own resources. The leading dot keeps it out of Ignition's resource scan.
     */
    public static final String WORKFLOW_PATH = ".github/workflows/ignition-sync.yml";

    private RunnerSetup() {
    }

    /** Turns any GitHub remote form into the https repository URL that {@code config.sh} wants. */
    public static String repoUrl(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return "";
        }
        String u = remoteUrl.trim();
        if (u.startsWith("git@")) {
            // git@github.com:owner/repo.git
            int colon = u.indexOf(':');
            if (colon > 0) {
                u = "https://" + u.substring(4, colon) + "/" + u.substring(colon + 1);
            }
        } else if (u.startsWith("ssh://")) {
            u = "https://" + u.substring("ssh://".length()).replaceFirst("^[^@]*@", "");
        }
        if (u.toLowerCase(Locale.ROOT).endsWith(".git")) {
            u = u.substring(0, u.length() - 4);
        }
        return u.replaceAll("/+$", "");
    }

    /** The shell that downloads, registers and installs the runner as a service (Linux). */
    public static String installScript(String remoteUrl, GitRunnerRecord cfg) {
        String repo = repoUrl(remoteUrl);
        String labels = cfg.getLabels();
        return String.join("\n",
                "# Run on the machine that will reach the gateway — NOT inside the gateway container.",
                "# Get REG_TOKEN from " + (repo.isEmpty() ? "<repo>" : repo)
                        + "/settings/actions/runners/new (it expires in an hour).",
                "REG_TOKEN=<paste the registration token>",
                "",
                "mkdir -p ~/actions-runner && cd ~/actions-runner",
                "curl -o runner.tar.gz -L https://github.com/actions/runner/releases/download/v"
                        + RUNNER_VERSION + "/actions-runner-linux-x64-" + RUNNER_VERSION + ".tar.gz",
                "tar xzf runner.tar.gz",
                "",
                "./config.sh --unattended \\",
                "  --url " + (repo.isEmpty() ? "<repo>" : repo) + " \\",
                "  --token \"$REG_TOKEN\" \\",
                "  --labels " + labels + " \\",
                "  --name ignition-$(hostname -s)",
                "",
                "sudo ./svc.sh install && sudo ./svc.sh start");
    }

    /**
     * The PowerShell that downloads, registers and installs the runner as a service (Windows).
     *
     * <p>Mirrors {@link #installScript}: run elevated on a machine that reaches the gateway, NOT
     * inside the container, using a registration token from the repository's runner settings that
     * expires in an hour. Installing as a service is fine here — the workflow this runner executes
     * makes exactly one HTTP call to the gateway, nothing more — so there is no Docker Desktop
     * session or interactive login for the service to depend on.
     */
    public static String installScriptWindows(String remoteUrl, GitRunnerRecord cfg) {
        String repo = repoUrl(remoteUrl);
        String labels = cfg.getLabels();
        return String.join("\n",
                "# Run in an ELEVATED PowerShell (service install needs it), on the machine that",
                "# will reach the gateway — NOT inside the gateway container.",
                "# Get $REG_TOKEN from " + (repo.isEmpty() ? "<repo>" : repo)
                        + "/settings/actions/runners/new (it expires in an hour).",
                "$REG_TOKEN = \"<paste the registration token>\"",
                "",
                "New-Item -ItemType Directory -Force C:\\actions-runner | Out-Null",
                "Set-Location C:\\actions-runner",
                "Invoke-WebRequest -Uri https://github.com/actions/runner/releases/download/v"
                        + RUNNER_VERSION + "/actions-runner-win-x64-" + RUNNER_VERSION
                        + ".zip -OutFile runner.zip",
                "Expand-Archive runner.zip -DestinationPath . -Force",
                "",
                ".\\config.cmd --unattended --url " + (repo.isEmpty() ? "<repo>" : repo)
                        + " --token $REG_TOKEN --labels " + labels
                        + " --name \"ignition-$env:COMPUTERNAME\" --runasservice");
    }

    /**
     * The workflow that runs on that runner and asks this gateway to sync.
     *
     * <p>The trigger branch is the project's own, not a hardcoded {@code main}. The module's
     * project-init creates {@code master}, so a workflow that assumed {@code main} would sit
     * there and never fire — no error, just nothing happening, which is the exact failure this
     * generator exists to prevent.
     *
     * <p>Two steps cover both runner platforms in one job: a bash/curl step for Linux and macOS,
     * and a {@code pwsh}/Invoke-RestMethod step for Windows, each gated on {@code runner.os} so
     * exactly one runs. A single bash step with line continuations (the pre-3.0.0 shape) failed
     * outright on a Windows runner, whose default shell is {@code pwsh}, not bash. Both forms
     * fail the step on a non-2xx response: {@code curl -f} exits non-zero, and
     * {@code Invoke-RestMethod} throws on an HTTP error status by default.
     */
    public static String workflowYaml(String project, String branch, GitRunnerRecord cfg) {
        String labels = "[" + String.join(", ", cfg.getLabels().split("\\s*,\\s*")) + "]";
        String base = cfg.getGatewayUrl().isEmpty() ? "<gateway url>" : cfg.getGatewayUrl();
        String onBranch = branch == null || branch.isBlank() ? "main" : branch.trim();
        String projectJson = project == null ? "" : project;
        return String.join("\n",
                "# " + WORKFLOW_PATH,
                "name: Sync to Ignition",
                "",
                "on:",
                "  push:",
                "    branches: [" + onBranch + "]",
                "",
                "jobs:",
                "  sync:",
                "    runs-on: " + labels,
                "    steps:",
                "      - name: Ask the gateway to pull (Linux/macOS)",
                "        if: runner.os != 'Windows'",
                "        run: |",
                "          curl -fsS -X POST \\",
                "            -H \"Authorization: Bearer $IGNITION_TOKEN\" \\",
                "            -H 'Content-Type: application/json' \\",
                "            -d '{\"project\":\"" + projectJson + "\"}' \\",
                "            " + base + "/data/git-config/runner-sync",
                "        env:",
                "          IGNITION_TOKEN: ${{ secrets.IGNITION_SYNC_TOKEN }}",
                "      - name: Ask the gateway to pull (Windows)",
                "        if: runner.os == 'Windows'",
                "        shell: pwsh",
                "        run: |",
                "          Invoke-RestMethod -Method Post -Uri \"" + base + "/data/git-config/runner-sync\" `",
                "            -Headers @{ Authorization = \"Bearer $env:IGNITION_TOKEN\" } `",
                "            -ContentType 'application/json' `",
                "            -Body '{\"project\":\"" + projectJson + "\"}'",
                "        env:",
                "          IGNITION_TOKEN: ${{ secrets.IGNITION_SYNC_TOKEN }}");
    }

    /** The one-liner that proves the runner can reach the gateway before any workflow runs. */
    public static String testCommand(String project, GitRunnerRecord cfg) {
        String base = cfg.getGatewayUrl().isEmpty() ? "<gateway url>" : cfg.getGatewayUrl();
        return "curl -fsS -X POST -H 'Authorization: Bearer <token>' "
                + "-H 'Content-Type: application/json' "
                + "-d '{\"project\":\"" + (project == null ? "" : project) + "\"}' "
                + base + "/data/git-config/runner-sync";
    }

    /**
     * The same check for a Windows runner machine.
     *
     * <p>The curl form above does not survive Windows PowerShell 5.1, where {@code curl} is an alias
     * for {@code Invoke-WebRequest} and takes none of those flags — so the one reachability check
     * would fail for a reason that has nothing to do with the gateway.
     */
    public static String testCommandWindows(String project, GitRunnerRecord cfg) {
        String base = cfg.getGatewayUrl().isEmpty() ? "<gateway url>" : cfg.getGatewayUrl();
        return "Invoke-RestMethod -Method Post -Uri \"" + base + "/data/git-config/runner-sync\" "
                + "-Headers @{ Authorization = \"Bearer <token>\" } "
                + "-ContentType 'application/json' "
                + "-Body '{\"project\":\"" + (project == null ? "" : project) + "\"}'";
    }
}
