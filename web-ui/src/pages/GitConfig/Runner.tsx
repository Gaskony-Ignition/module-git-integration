import React from "react";
import { Button, Loading, Radio, useToastNotifications } from "../../webui";
// Label-painting wrappers — the platform inputs render `label` into an invisible notch legend.
import { SelectInput, TextInput } from "./fields";
import {
  useCommitRunnerWorkflowMutation,
  useGetRunnerQuery,
  useSaveRunnerMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";

// A self-hosted runner needs no inbound path to the gateway, which is the whole reason it is
// here rather than a webhook. Everything on this tab is generated setup: the values involved
// (repository, labels, gateway address, token, project) have to agree across two systems and
// three files, and one of them being subtly wrong produces a workflow that queues forever.

/** A copyable block. The copy button matters — these are long and retyping one is how it breaks. */
function Snippet({ text, label }: { text: string; label: string }) {
  const toasts = useToastNotifications();
  return (
    <div className="gitcfg-snippet">
      <div className="gitcfg-snippet-bar">
        <span>{label}</span>
        <button
          type="button"
          onClick={() => {
            navigator.clipboard
              ?.writeText(text)
              .then(() => toasts.notifySuccess("Copied"))
              .catch(() =>
                toasts.notifyError("Could not copy — select the text instead")
              );
          }}
        >
          Copy
        </button>
      </div>
      <pre>{text}</pre>
    </div>
  );
}

export default function Runner() {
  const toasts = useToastNotifications();
  // Nothing is pre-selected: until a project is chosen the snippets show placeholders.
  const [project, setProject] = React.useState("");
  const { data, isLoading, refetch } = useGetRunnerQuery(project || undefined);
  const [save, { isLoading: saving }] = useSaveRunnerMutation();
  const [commitWorkflow, { isLoading: committing }] =
    useCommitRunnerWorkflowMutation();

  const [gatewayUrl, setGatewayUrl] = React.useState("");
  const [labels, setLabels] = React.useState("");
  const [enabled, setEnabled] = React.useState(false);
  // Held only until the page is left. The gateway returns it once and cannot return it again.
  const [issued, setIssued] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!data) return;
    // An unsaved address starts as the one this page was opened on. It is only a starting point:
    // on a container or behind a proxy the runner usually needs a different one.
    setGatewayUrl(data.gatewayUrl || window.location.origin);
    setLabels(data.labels);
    setEnabled(data.enabled);
  }, [data]);

  if (isLoading || !data) return <Loading />;

  const onSave = async (extra: Record<string, unknown> = {}) => {
    try {
      const res = await save({
        enabled,
        gatewayUrl,
        labels,
        ...extra,
      }).unwrap();
      if (res.token) setIssued(res.token);
      else toasts.notifySuccess("Saved");
      refetch();
    } catch (e) {
      errorToast(toasts, "Runner settings failed")(e);
    }
  };

  const onMode = async (mode: "release" | "repo") => {
    try {
      await save({ project, mode }).unwrap();
      refetch();
    } catch (e) {
      errorToast(toasts, "Could not change the delivery")(e);
    }
  };

  const chosen = data.project !== "";
  const release = data.mode === "release";

  return (
    <>
      <p className="gitcfg-auto-hint">
        A self-hosted runner is a small service that connects out to GitHub and
        is handed workflow jobs over that same connection, so nothing has to
        reach in. Install it on the host, not inside a gateway container: a
        runner on a Docker host reaches its gateways on their published ports. A
        workflow step then calls this gateway, either to install a release or to
        pull the latest commits.
      </p>

      <h4 className="gitcfg-step">1 · Let the runner call this gateway</h4>
      <label className="gitcfg-check">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => setEnabled(e.target.checked)}
        />
        <span>Accept requests from a runner</span>
      </label>
      <div className="gitcfg-auto-pair">
        <TextInput
          label="Gateway address the runner will use"
          value={gatewayUrl}
          placeholder="http://gateway.plant.local:8088"
          onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
            setGatewayUrl(e.target.value)
          }
        />
        <TextInput
          label="Runner labels"
          value={labels}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
            setLabels(e.target.value)
          }
        />
      </div>
      <p className="gitcfg-auto-hint">
        Filled in with the address this page is open on. Change it to the one
        the <em>runner</em> reaches this gateway on — for a runner on the same
        Docker host that is usually <code>http://localhost:</code> plus the
        published port.
      </p>
      <Button disabled={saving} onClick={() => onSave()}>
        Save
      </Button>

      <h4 className="gitcfg-step">2 · Generate a token</h4>
      <p className="gitcfg-auto-hint">
        {data.hasToken
          ? "A token is set. Generating a new one immediately stops the old one working."
          : "No token yet. Until one exists the gateway answers the runner with a 404."}{" "}
        The workflow sends it with every call, so only your workflows can make
        this gateway install or pull. Save it in GitHub as the secret{" "}
        <code>IGNITION_SYNC_TOKEN</code> — on the repository, or on the
        organisation to share it.
      </p>
      <Button
        colorClass="secondary"
        disabled={saving}
        onClick={() => onSave({ generateToken: true })}
      >
        {data.hasToken ? "Generate a new token" : "Generate token"}
      </Button>

      {issued ? (
        <div className="gitcfg-token-once">
          <strong>Copy this now — it is not shown again.</strong>
          <Snippet
            label="Add as the secret IGNITION_SYNC_TOKEN"
            text={issued}
          />
        </div>
      ) : null}

      <h4 className="gitcfg-step">
        3 · Choose a project and how it is delivered
      </h4>
      {data.projects.length === 0 ? (
        <p className="gitcfg-empty">No projects on this gateway yet.</p>
      ) : (
        <SelectInput
          label="Project"
          value={project}
          values={[
            { label: "Choose a project…", value: "" },
            ...data.projects.map((p) => ({ label: p.name, value: p.name })),
          ]}
          onChange={(e: unknown) => setProject(selectValue(e) || "")}
        />
      )}
      {chosen ? (
        <>
          <div className="gitcfg-cred-row">
            <Radio
              name="runner-mode"
              value={data.mode}
              radios={[
                {
                  label: "Release — a release zip replaces the whole project",
                  value: "release",
                },
                {
                  label: "Repo updates — pull the latest commits on the branch",
                  value: "repo",
                  disabled: !data.hasRemote,
                },
              ]}
              onChange={(_e: unknown, value: string) =>
                onMode(value as "release" | "repo")
              }
            />
          </div>
          <p className="gitcfg-auto-hint">
            {release
              ? "On a version tag the workflow uploads the project export. The gateway replaces the project with it — files removed from the release disappear — keeps its own git repository and project properties, and applies it without a restart. The project needs no repository on this gateway."
              : "On a push to the branch the workflow asks the gateway to pull. The project must be a repository with a remote (Projects tab); it pulls with the credential that remote already uses."}
            {!data.hasRemote
              ? " Repo updates needs the project set up with a remote on the Projects tab first."
              : ""}
          </p>
        </>
      ) : (
        <p className="gitcfg-auto-hint">
          The commands below show placeholders until a project is chosen.
        </p>
      )}

      <h4 className="gitcfg-step">4 · Install the runner</h4>
      <p className="gitcfg-auto-hint">
        Once per machine — one runner serves every gateway it can reach. The
        registration token comes from{" "}
        {data.repoUrl ? (
          <code>{data.repoUrl}/settings/actions/runners/new</code>
        ) : (
          "the repository's (or organisation's) Actions runner settings"
        )}{" "}
        and expires in an hour.
      </p>
      <Snippet label="On Linux" text={data.installScript} />
      <Snippet label="On macOS" text={data.installScriptMac} />
      <Snippet
        label="On Windows (elevated PowerShell)"
        text={data.installScriptWindows}
      />

      <h4 className="gitcfg-step">5 · Add the workflow</h4>
      {release ? (
        <p className="gitcfg-auto-hint">
          Add this to the repository that builds the release. If it already has
          a deployment workflow, keep your own build and add only the upload
          step, pointing at your zip.
        </p>
      ) : (
        <>
          <p className="gitcfg-auto-hint">
            The gateway already has push rights to this repository, so it can
            commit the workflow itself. It refuses to overwrite a different
            workflow that is already there.
          </p>
          <Button
            disabled={committing || !gatewayUrl || !chosen || !data.hasRemote}
            onClick={async () => {
              try {
                const r = await commitWorkflow({
                  project: data.project,
                }).unwrap();
                if (r.unchanged) toasts.notifySuccess("Already committed");
                else if (r.pushed) toasts.notifySuccess("Committed and pushed");
                else
                  toasts.notify({
                    type: "error",
                    title: "Committed, but the push failed",
                    message:
                      r.pushError || "Push the project from the Designer.",
                    autoClose: false,
                    isDismissible: true,
                  });
              } catch (e) {
                errorToast(toasts, "Could not commit the workflow")(e);
              }
            }}
          >
            Commit the workflow to the repository
          </Button>
          <p className="gitcfg-auto-hint">Or copy it in by hand:</p>
        </>
      )}
      <Snippet label={data.workflowPath} text={data.workflowYaml} />

      <h4 className="gitcfg-step">Check it before you rely on it</h4>
      <p className="gitcfg-auto-hint">
        {release
          ? "From the runner machine, with the token in place of the placeholder. It sends no zip, so nothing is installed: 400 “no release zip” means the address and token are right."
          : "From the runner machine, with the token in place of the placeholder. A success means the workflow will work."}
      </p>
      <Snippet label="Test from Linux or macOS" text={data.testCommand} />
      <Snippet
        label="Test from Windows (PowerShell)"
        text={data.testCommandWindows}
      />
    </>
  );
}
