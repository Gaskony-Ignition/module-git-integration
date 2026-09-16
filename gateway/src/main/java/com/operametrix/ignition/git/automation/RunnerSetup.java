package com.operametrix.ignition.git.automation;

import com.operametrix.ignition.git.records.GitRunnerRecord;

import java.util.Locale;

/**
 * Generates the copy-and-paste setup for a GitHub Actions self-hosted runner.
 *
 * <p>None of this is clever — it is the standard runner setup with this gateway's values already
 * substituted in. The reason it exists is that the values involved (repository URL, runner
 * labels, gateway address, token, project) have to agree across two systems and three files, and
 * getting one of them subtly wrong produces a workflow that queues forever with no error.
 *
 * <p>Every generator takes a null or blank project or repository and emits a placeholder. The
 * snippets are read as examples, so they must never carry a real project name nobody chose.
 *
 * <p>The gateway deliberately does not install or supervise the runner itself. A runner executes
 * whatever the workflow file says; hosting one from inside the module would put arbitrary
 * repository-supplied shell next to the project store, under the gateway's own identity.
 */
public final class RunnerSetup {

    /**
     * The runner release the generated commands download. An older runner self-updates on first
     * connection, so this going stale costs a minute rather than a failure.
     */
    private static final String RUNNER_VERSION = "2.337.0";

    /**
     * Where the repo-updates workflow lives. GitHub reads workflows only from the repository root,
     * and for a project repository the root IS the project folder. The leading dot keeps it out of
     * Ignition's resource scan.
     */
    public static final String WORKFLOW_PATH = ".github/workflows/ignition-sync.yml";

    /** Where the release workflow lives, beside any deployment workflow the repository has. */
    public static final String RELEASE_WORKFLOW_PATH = ".github/workflows/ignition-release.yml";

    private static final String PROJECT_PLACEHOLDER = "<project>";
    private static final String REPO_PLACEHOLDER = "<repository url>";
    private static final String ORG_PLACEHOLDER = "<organisation url>";
    private static final String GATEWAY_PLACEHOLDER = "<gateway url>";

    /** A runner registers against one repository. */
    public static final String SCOPE_REPO = "repo";

    /** A runner registers against the whole organisation and serves every repository in it. */
    public static final String SCOPE_ORG = "org";

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

    /**
     * The owning organisation's URL — the repository URL minus its last path segment.
     *
     * <p>A runner registers at exactly one scope, and this is the one that matters most of the
     * time: a machine standing beside a gateway usually receives from several project
     * repositories, and a repository-scoped runner serves only the one it was registered against.
     * GitHub's own answer to this is runner groups, which private repositories cannot use below
     * the Team plan, so scope plus labels is the portable way to do it.
     */
    public static String orgUrl(String remoteUrl) {
        String r = repoUrl(remoteUrl);
        int slash = r.lastIndexOf('/');
        // Below three slashes there is no owner segment left — "https://github.com" itself.
        return slash <= "https://".length() ? "" : r.substring(0, slash);
    }

    private static String org(String remoteUrl) {
        String o = orgUrl(remoteUrl);
        return o.isEmpty() ? ORG_PLACEHOLDER : o;
    }

    /** Where {@code config.sh --url} points, and where the registration token is minted. */
    private static String registerAt(String remoteUrl, String scope) {
        return SCOPE_ORG.equals(scope) ? org(remoteUrl) : repo(remoteUrl);
    }

    /**
     * The runner labels as {@code config.sh}/{@code config.cmd} need them: comma-separated with no
     * spaces. The field is free text and people type {@code self-hosted, ignition}; pasted raw, the
     * space splits it into two shell arguments and registration fails on a stray {@code ignition}.
     */
    static String labelsArg(GitRunnerRecord cfg) {
        String raw = cfg.getLabels() == null ? "" : cfg.getLabels();
        return String.join(",", java.util.Arrays.stream(raw.split(","))
                .map(String::trim).filter(l -> !l.isEmpty()).toList());
    }

    private static String project(String project) {
        return project == null || project.isBlank() ? PROJECT_PLACEHOLDER : project;
    }

    private static String repo(String remoteUrl) {
        String r = repoUrl(remoteUrl);
        return r.isEmpty() ? REPO_PLACEHOLDER : r;
    }

    private static String base(GitRunnerRecord cfg) {
        return cfg.getGatewayUrl().isEmpty() ? GATEWAY_PLACEHOLDER : cfg.getGatewayUrl();
    }

    /** The two comment lines that say where to register and where the token comes from. */
    private static String scopeNote(String remoteUrl, String scope, String comment) {
        String at = registerAt(remoteUrl, scope);
        return SCOPE_ORG.equals(scope)
                ? comment + " Registering at the ORGANISATION: this one runner then serves every\n"
                        + comment + " repository in it. Get the token from "
                        + at + "/settings/actions/runners/new"
                : comment + " Registering at this REPOSITORY: the runner serves only it. Get the\n"
                        + comment + " token from " + at + "/settings/actions/runners/new";
    }

    /** Linux and macOS share the tarball install and differ only in the build and the service. */
    private static String unixInstall(String remoteUrl, GitRunnerRecord cfg, String scope,
                                      String platform, String service) {
        return String.join("\n",
                "# Run on the machine that will reach the gateway — the host, NOT inside a gateway",
                "# container. A runner on a Docker host reaches its gateways on their published ports.",
                "# One runner serves every gateway and repository it can reach, so this is once per",
                "# machine, not once per project.",
                scopeNote(remoteUrl, scope, "#"),
                "# The token expires in an hour.",
                "REG_TOKEN=<paste the registration token>",
                "",
                "mkdir -p ~/actions-runner && cd ~/actions-runner",
                "curl -o runner.tar.gz -L https://github.com/actions/runner/releases/download/v"
                        + RUNNER_VERSION + "/actions-runner-" + platform + "-" + RUNNER_VERSION + ".tar.gz",
                "tar xzf runner.tar.gz",
                "",
                "# --labels is what routes jobs here: a workflow's runs-on must list the same ones.",
                "./config.sh --unattended \\",
                "  --url " + registerAt(remoteUrl, scope) + " \\",
                "  --token \"$REG_TOKEN\" \\",
                "  --labels " + labelsArg(cfg) + " \\",
                "  --name ignition-$(hostname -s)",
                "",
                service);
    }

    /** The shell that downloads, registers and installs the runner as a service (Linux). */
    public static String installScript(String remoteUrl, GitRunnerRecord cfg, String scope) {
        return unixInstall(remoteUrl, cfg, scope, "linux-x64",
                "sudo ./svc.sh install && sudo ./svc.sh start");
    }

    /**
     * The same for macOS. The runner ships a separate Apple-silicon build, and its {@code svc.sh}
     * installs a launchd agent for the logged-in user, so it runs without sudo.
     */
    public static String installScriptMac(String remoteUrl, GitRunnerRecord cfg, String scope) {
        return unixInstall(remoteUrl, cfg, scope, "osx-arm64", "./svc.sh install && ./svc.sh start");
    }

    /**
     * The PowerShell that downloads, registers and installs the runner as a service (Windows).
     * Run elevated, on the host rather than inside a container.
     */
    public static String installScriptWindows(String remoteUrl, GitRunnerRecord cfg, String scope) {
        return String.join("\n",
                "# Run in an ELEVATED PowerShell (service install needs it), on the machine that",
                "# will reach the gateway — the host, NOT inside a gateway container.",
                "# One runner serves every gateway and repository it can reach.",
                scopeNote(remoteUrl, scope, "#"),
                "# The token expires in an hour.",
                "$REG_TOKEN = \"<paste the registration token>\"",
                "",
                "New-Item -ItemType Directory -Force C:\\actions-runner | Out-Null",
                "Set-Location C:\\actions-runner",
                "Invoke-WebRequest -Uri https://github.com/actions/runner/releases/download/v"
                        + RUNNER_VERSION + "/actions-runner-win-x64-" + RUNNER_VERSION
                        + ".zip -OutFile runner.zip",
                "Expand-Archive runner.zip -DestinationPath . -Force",
                "",
                "# --labels is what routes jobs here: a workflow's runs-on must list the same ones.",
                ".\\config.cmd --unattended --url " + registerAt(remoteUrl, scope)
                        + " --token $REG_TOKEN --labels " + labelsArg(cfg)
                        + " --name \"ignition-$env:COMPUTERNAME\" --runasservice");
    }

    private static String runsOn(GitRunnerRecord cfg) {
        return "[" + String.join(", ", labelsArg(cfg).split(",")) + "]";
    }

    /**
     * The repo-updates workflow: on a push to the project's branch, ask this gateway to pull.
     *
     * <p>The trigger branch is the project's own, not a hardcoded {@code main} — project-init
     * creates {@code master}, and a workflow waiting on {@code main} never fires and never errors.
     * Two steps gated on {@code runner.os} cover both shells, because a Windows runner's default
     * shell is pwsh and a bash step fails there.
     */
    public static String workflowYaml(String project, String branch, GitRunnerRecord cfg) {
        String base = base(cfg);
        String onBranch = branch == null || branch.isBlank() ? "main" : branch.trim();
        String p = project(project);
        return String.join("\n",
                "# " + WORKFLOW_PATH,
                "#",
                "# An EXAMPLE. If the repository already has a deployment workflow, add the step",
                "# below to it rather than adding this file — two workflows deploying the same",
                "# project will fight.",
                "#",
                "# Three values have to agree with the Actions runner tab, and nothing checks them:",
                "#   runs-on   the labels the runner was registered with (a mismatch queues for ever)",
                "#   the URL   the address the RUNNER reaches this gateway on, not the one you browse",
                "#   the secret  a repository or organisation secret holding the generated token",
                "name: Sync to Ignition",
                "",
                "on:",
                "  push:",
                "    branches: [" + onBranch + "]",
                "",
                "jobs:",
                "  sync:",
                "    runs-on: " + runsOn(cfg),
                "    steps:",
                "      - name: Ask the gateway to pull (Linux/macOS)",
                "        if: runner.os != 'Windows'",
                "        run: |",
                "          curl -fsS -X POST \\",
                "            -H \"Authorization: Bearer $IGNITION_TOKEN\" \\",
                "            -H 'Content-Type: application/json' \\",
                "            -d '{\"project\":\"" + p + "\"}' \\",
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
                "            -Body '{\"project\":\"" + p + "\"}'",
                "        env:",
                "          IGNITION_TOKEN: ${{ secrets.IGNITION_SYNC_TOKEN }}");
    }

    /**
     * The release workflow: on a version tag, zip the project and upload it to this gateway.
     *
     * <p>The zip is the repository root minus git's own folders, which is a project export when the
     * project sits at the root — the only layout the module supports. A repository that already
     * builds its release (a packaging script, a deployment workflow) keeps its own build and only
     * needs the upload step, with its zip in place of {@code dist/release.zip}.
     */
    public static String releaseWorkflowYaml(String project, GitRunnerRecord cfg) {
        String base = base(cfg);
        String url = base + "/data/git-config/runner-release?project=" + project(project);
        return String.join("\n",
                "# " + RELEASE_WORKFLOW_PATH,
                "#",
                "# An EXAMPLE. If the repository already builds and deploys its release, keep all of",
                "# that and copy only the upload step, pointing it at the zip you already produce.",
                "#",
                "# Three values have to agree with the Actions runner tab, and nothing checks them:",
                "#   runs-on   the labels the runner was registered with (a mismatch queues for ever)",
                "#   the URL   the address the RUNNER reaches this gateway on, not the one you browse",
                "#   the secret  a repository or organisation secret holding the generated token",
                "#",
                "# The gateway needs no git credentials for this: the runner does every git",
                "# operation and the gateway only receives an authenticated zip.",
                "name: Release to Ignition",
                "",
                "on:",
                "  push:",
                "    tags: ['v*']",
                "",
                "jobs:",
                "  release:",
                "    runs-on: " + runsOn(cfg),
                "    steps:",
                "      - uses: actions/checkout@v4",
                "",
                "      # Build the project export. Replace these two steps with your own packaging if you",
                "      # have one; the upload only needs a zip with project.json at its root.",
                "      - name: Zip the project (Linux/macOS)",
                "        if: runner.os != 'Windows'",
                "        run: |",
                "          mkdir -p dist",
                "          zip -qr dist/release.zip . -x '.git/*' '.github/*' 'dist/*'",
                "      - name: Zip the project (Windows)",
                "        if: runner.os == 'Windows'",
                "        shell: pwsh",
                "        run: |",
                "          New-Item -ItemType Directory -Force dist | Out-Null",
                "          $items = Get-ChildItem -Force | Where-Object { $_.Name -notin '.git', '.github', 'dist' }",
                "          Compress-Archive -Path $items.FullName -DestinationPath dist/release.zip -Force",
                "",
                "      # The gateway replaces the whole project with the zip, keeping its own git",
                "      # repository and project properties, and applies it without a restart.",
                "      - name: Upload the release (Linux/macOS)",
                "        if: runner.os != 'Windows'",
                "        run: |",
                "          curl -fsS -X POST \\",
                "            -H \"Authorization: Bearer $IGNITION_TOKEN\" \\",
                "            -H 'Content-Type: application/zip' \\",
                "            --data-binary @dist/release.zip \\",
                "            \"" + url + "&version=$GITHUB_REF_NAME\"",
                "        env:",
                "          IGNITION_TOKEN: ${{ secrets.IGNITION_SYNC_TOKEN }}",
                "      - name: Upload the release (Windows)",
                "        if: runner.os == 'Windows'",
                "        shell: pwsh",
                "        run: |",
                "          Invoke-RestMethod -Method Post `",
                "            -Uri \"" + url + "&version=$env:GITHUB_REF_NAME\" `",
                "            -Headers @{ Authorization = \"Bearer $env:IGNITION_TOKEN\" } `",
                "            -ContentType 'application/zip' -InFile dist/release.zip",
                "        env:",
                "          IGNITION_TOKEN: ${{ secrets.IGNITION_SYNC_TOKEN }}");
    }

    /** The one-liner that proves the runner can reach the gateway before any workflow runs. */
    public static String testCommand(String project, GitRunnerRecord cfg) {
        return "curl -fsS -X POST -H 'Authorization: Bearer <token>' "
                + "-H 'Content-Type: application/json' "
                + "-d '{\"project\":\"" + project(project) + "\"}' "
                + base(cfg) + "/data/git-config/runner-sync";
    }

    /**
     * The same check for a Windows runner machine. On Windows PowerShell 5.1 {@code curl} is an
     * alias for {@code Invoke-WebRequest} and takes none of the curl flags.
     */
    public static String testCommandWindows(String project, GitRunnerRecord cfg) {
        return "Invoke-RestMethod -Method Post -Uri \"" + base(cfg) + "/data/git-config/runner-sync\" "
                + "-Headers @{ Authorization = \"Bearer <token>\" } "
                + "-ContentType 'application/json' "
                + "-Body '{\"project\":\"" + project(project) + "\"}'";
    }

    /**
     * Release-mode check. It sends NO zip on purpose: a real one would replace the project. The
     * gateway answers 400 "no release zip" once the address and token are right — 401 means the
     * token is wrong, 404 that the runner is switched off here.
     */
    public static String releaseTestCommand(String project, GitRunnerRecord cfg) {
        return "curl -sS -X POST -H 'Authorization: Bearer <token>' \""
                + base(cfg) + "/data/git-config/runner-release?project=" + project(project)
                + "\"   # expect 400 {\"error\":\"no release zip in the request body\"}";
    }

    public static String releaseTestCommandWindows(String project, GitRunnerRecord cfg) {
        return "try { Invoke-RestMethod -Method Post -Uri \"" + base(cfg)
                + "/data/git-config/runner-release?project=" + project(project) + "\" "
                + "-Headers @{ Authorization = \"Bearer <token>\" } } "
                + "catch { $_.Exception.Response.StatusCode.value__ }   # expect 400";
    }
}
