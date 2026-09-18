package com.operametrix.ignition.git.automation;

/**
 * Generates the one command that proves a runner can reach this gateway.
 *
 * <p>Installing the runner and writing the workflow are GitHub's steps, done with GitHub's own
 * instructions — the page lists what each needs rather than generating a version of it. What the
 * gateway can usefully generate is the check, because only the gateway knows the route, and a wrong
 * address is the failure people spend longest on. The address is whatever the page was given for
 * this check; the gateway stores none.
 *
 * <p>A null or blank project emits a placeholder: the command is read as an example and must never
 * carry a real project name nobody chose.
 */
public final class RunnerSetup {

    private static final String PROJECT_PLACEHOLDER = "<project>";
    private static final String GATEWAY_PLACEHOLDER = "<gateway url>";

    private RunnerSetup() {
    }

    private static String project(String project) {
        return project == null || project.isBlank() ? PROJECT_PLACEHOLDER : project;
    }

    private static String base(String gatewayUrl) {
        return gatewayUrl == null || gatewayUrl.isBlank()
                ? GATEWAY_PLACEHOLDER : gatewayUrl.trim().replaceAll("/+$", "");
    }

    /** The one-liner that proves the runner can reach the gateway before any workflow runs. */
    public static String testCommand(String project, String gatewayUrl) {
        return "curl -fsS -X POST -H 'Authorization: Bearer <token>' "
                + "-H 'Content-Type: application/json' "
                + "-d '{\"project\":\"" + project(project) + "\"}' "
                + base(gatewayUrl) + "/data/git-config/runner-sync";
    }

    /**
     * The same check for a Windows runner machine. On Windows PowerShell 5.1 {@code curl} is an
     * alias for {@code Invoke-WebRequest} and takes none of the curl flags.
     */
    public static String testCommandWindows(String project, String gatewayUrl) {
        return "Invoke-RestMethod -Method Post -Uri \"" + base(gatewayUrl) + "/data/git-config/runner-sync\" "
                + "-Headers @{ Authorization = \"Bearer <token>\" } "
                + "-ContentType 'application/json' "
                + "-Body '{\"project\":\"" + project(project) + "\"}'";
    }

    /**
     * Release-mode check. It sends NO zip on purpose: a real one would replace the project. The
     * gateway answers 400 "no release zip" once the address and token are right — 401 means the
     * token is wrong, 404 that the runner is switched off here.
     */
    public static String releaseTestCommand(String project, String gatewayUrl) {
        return "curl -sS -X POST -H 'Authorization: Bearer <token>' \""
                + base(gatewayUrl) + "/data/git-config/runner-release?project=" + project(project)
                + "\"   # expect 400 {\"error\":\"no release zip in the request body\"}";
    }

    public static String releaseTestCommandWindows(String project, String gatewayUrl) {
        return "try { Invoke-RestMethod -Method Post -Uri \"" + base(gatewayUrl)
                + "/data/git-config/runner-release?project=" + project(project) + "\" "
                + "-Headers @{ Authorization = \"Bearer <token>\" } } "
                + "catch { $_.Exception.Response.StatusCode.value__ }   # expect 400";
    }
}
