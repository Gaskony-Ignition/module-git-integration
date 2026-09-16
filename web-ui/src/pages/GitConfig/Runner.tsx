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
// here rather than a webhook.
//
// Two things on this tab are settings and the rest is generated text, and the page used to make
// them look alike. The gateway acts on exactly two values — whether it accepts runner requests,
// and the token — and reads neither the address nor the labels when a runner calls. So those two
// live beside the commands they fill in, under a heading that says so, and can be previewed
// before they are saved. Getting this wrong is what made the tab read as five compulsory steps.

type Os = "linux" | "mac" | "windows";

const OS_LABELS: [Os, string][] = [
  ["linux", "Linux"],
  ["mac", "macOS"],
  ["windows", "Windows"],
];

/** The OS of the machine showing this page — a starting point, not an answer. */
function detectOs(): Os {
  const nav = navigator as Navigator & {
    userAgentData?: { platform?: string };
  };
  const p = (
    nav.userAgentData?.platform ||
    navigator.platform ||
    ""
  ).toLowerCase();
  if (p.includes("mac")) return "mac";
  if (p.includes("win")) return "windows";
  return "linux";
}

/**
 * DD/MM/YYYY and 24-hour, whatever the browser's locale is. The Event log beside this renders
 * timestamps from the gateway in that shape, and a US-formatted one next to it reads as a
 * different day: 09/10 is either September or October depending on which line you are on.
 */
function formatWhen(ms: number): string {
  return new Date(ms).toLocaleString("en-AU", { hour12: false });
}

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

/** One snippet at a time. Three stacked blocks made every step three screens tall. */
function OsTabs({
  os,
  setOs,
  snippets,
  label,
}: {
  os: Os;
  setOs: (o: Os) => void;
  snippets: Record<Os, string>;
  label: string;
}) {
  return (
    <div className="gitcfg-os">
      <div className="gitcfg-subtabs" role="tablist">
        {OS_LABELS.map(([key, text]) => (
          <button
            key={key}
            role="tab"
            aria-selected={os === key}
            className={os === key ? "is-active" : ""}
            onClick={() => setOs(key)}
          >
            {text}
          </button>
        ))}
      </div>
      <Snippet label={label} text={snippets[os]} />
    </div>
  );
}

export default function Runner({ onHelp }: { onHelp?: () => void }) {
  const toasts = useToastNotifications();
  // Nothing is pre-selected: until a project is chosen the snippets show placeholders.
  const [project, setProject] = React.useState("");
  const [scope, setScope] = React.useState<"repo" | "org">("org");
  const [os, setOs] = React.useState<Os>(detectOs);

  const [gatewayUrl, setGatewayUrl] = React.useState("");
  const [labels, setLabels] = React.useState("");
  const [enabled, setEnabled] = React.useState(false);
  // Held only until the page is left. The gateway returns it once and cannot return it again.
  const [issued, setIssued] = React.useState<string | null>(null);
  const [showWorkflow, setShowWorkflow] = React.useState(false);
  const [showInstall, setShowInstall] = React.useState(false);

  // What the snippets are generated from. Debounced so a keystroke is not a request, and only
  // ever affects the generated text — see the note on the query in GitConfig.service.ts.
  const [preview, setPreview] = React.useState({ gatewayUrl: "", labels: "" });
  React.useEffect(() => {
    const t = setTimeout(() => setPreview({ gatewayUrl, labels }), 300);
    return () => clearTimeout(t);
  }, [gatewayUrl, labels]);

  const { data, isLoading, refetch } = useGetRunnerQuery({
    project: project || undefined,
    scope,
    labels: preview.labels || undefined,
    gatewayUrl: preview.gatewayUrl || undefined,
  });
  const [save, { isLoading: saving }] = useSaveRunnerMutation();
  const [commitWorkflow, { isLoading: committing }] =
    useCommitRunnerWorkflowMutation();

  const loaded = React.useRef(false);
  React.useEffect(() => {
    // Only the first response seeds the fields — later ones carry the values being previewed,
    // and reseeding from those would fight whatever is being typed.
    if (!data || loaded.current) return;
    loaded.current = true;
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
  const unsaved = gatewayUrl !== data.gatewayUrl || labels !== data.labels;

  const seenAt = data.runnerSeen?.at ?? 0;
  const wf = data.workflows?.list ?? [];
  const wired = wf.filter((w) => w.callsGateway);

  const installSnippets: Record<Os, string> = {
    linux: data.installScript,
    mac: data.installScriptMac,
    windows: data.installScriptWindows,
  };
  const testSnippets: Record<Os, string> = {
    linux: data.testCommand,
    mac: data.testCommand,
    windows: data.testCommandWindows,
  };

  return (
    <>
      <p className="gitcfg-auto-hint">
        A self-hosted runner is a small service that connects out to GitHub and
        is handed workflow jobs over that same connection, so nothing has to
        reach in. Install it on the host, not inside a gateway container: a
        runner on a Docker host reaches its gateways on their published ports. A
        workflow step then calls this gateway, either to install a release or to
        pull the latest commits.{" "}
        {onHelp ? (
          <button type="button" className="gitcfg-linkish" onClick={onHelp}>
            Is a runner the right choice here?
          </button>
        ) : null}
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
      <p className="gitcfg-auto-hint">
        This and the token below are the whole of what the gateway acts on.
        Everything further down generates the commands you run elsewhere.
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
        this gateway install or pull. The gateway keeps its own copy in
        Ignition&apos;s secret store to check against; GitHub needs a copy of
        its own to send. Save it there as the secret{" "}
        <code>IGNITION_SYNC_TOKEN</code>, on the repository — or on the
        organisation to share it, which GitHub allows for private repositories
        only on Team plans and above. Runner scope and secret scope are
        unrelated: an organisation runner works perfectly well with a repository
        secret.
        <br />
        <strong>
          One token per gateway, not per project or repository.
        </strong>{" "}
        Every repository that deploys here holds a copy of this one value; a
        second gateway has a token of its own, under a secret name of its own.
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
        <>
          {/* Every project and its delivery, without having to open the select to find out.
              The select below chooses what to EDIT and is deliberately not remembered, which
              reads as a lost setting unless the settings themselves are on show. */}
          <table className="gitcfg-proj-table">
            <thead>
              <tr>
                <th>Project</th>
                <th>Delivered as</th>
                <th>Has a remote</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {data.projects.map((p) => (
                <tr key={p.name}>
                  <td>
                    <span className="gitcfg-proj-name">{p.name}</span>
                  </td>
                  <td>
                    {p.mode === "repo" ? (
                      "Repo updates"
                    ) : p.mode === "release" ? (
                      "Release"
                    ) : (
                      <span className="gitcfg-proj-off">Not chosen</span>
                    )}
                  </td>
                  <td>
                    {p.hasRemote ? (
                      <span className="gitcfg-proj-ok">Yes</span>
                    ) : (
                      <span className="gitcfg-proj-off">No</span>
                    )}
                  </td>
                  <td className="gitcfg-proj-act">
                    <Button
                      colorClass="secondary"
                      onClick={() => setProject(p.name)}
                    >
                      {project === p.name ? "Editing" : "Edit"}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="gitcfg-auto-hint">
            Every project is listed. There is nothing to add: a project receives
            through the runner as soon as the gateway accepts requests and the
            workflow calls it, and the delivery above is how it will be applied.
            One token covers them all — it belongs to this gateway, not to a
            project or a repository — so every repository deploying here holds a
            copy of the same secret.
          </p>
          <SelectInput
            label="Project to edit"
            value={project}
            values={[
              { label: "Choose a project…", value: "" },
              ...data.projects.map((p) => ({ label: p.name, value: p.name })),
            ]}
            onChange={(e: unknown) => setProject(selectValue(e) || "")}
          />
        </>
      )}
      {chosen ? (
        <>
          {/* The CHOSEN mode, not the effective one. Showing the default as a selected radio
              made an undecided project look decided, and because clicking the option already
              shown fires no change, the setting could not be reached at all. */}
          <div className="gitcfg-cred-row">
            <Radio
              name="runner-mode"
              value={data.chosenMode}
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
          {!data.chosenMode ? (
            <p className="gitcfg-auto-hint">
              <strong>Not chosen yet.</strong> Both routes are accepted for this
              project, and a workflow aimed at either will be obeyed. Pick one
              to hold the project to it — the other then answers with an error
              instead of quietly doing the opposite.
            </p>
          ) : null}
          <p className="gitcfg-auto-hint">
            {release
              ? "On a version tag the workflow uploads the project export. The gateway replaces the project with it — files removed from the release disappear — keeps its own git repository and project properties, and applies it without a restart. This gateway needs no git credentials and no repository at all: the runner does every git operation and the gateway only receives an authenticated zip, which is why it suits a gateway with no internet access."
              : "On a push to the branch the workflow asks the gateway to pull. This gateway does the git work itself, so the project must be a repository with a remote (Projects tab) and pulls with the credential that remote already uses."}
            {!data.hasRemote
              ? " Repo updates needs the project set up with a remote on the Projects tab first."
              : ""}
          </p>
          {data.chosenMode ? (
            <p className="gitcfg-auto-hint">
              The gateway holds this choice to it: it refuses the other route
              for this project rather than quietly doing the other thing, so a
              workflow aimed at the wrong one fails where you can see it.
            </p>
          ) : null}
        </>
      ) : (
        <p className="gitcfg-auto-hint">
          The commands below show placeholders until a project is chosen.
        </p>
      )}

      <h4 className="gitcfg-step">Values the generated commands use</h4>
      <p className="gitcfg-auto-hint">
        Neither of these is a gateway setting — the gateway reads neither when a
        runner calls. They fill in the commands below, and are saved so this
        page can regenerate them later.
      </p>
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
          label="Runner labels the job must match"
          value={labels}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
            setLabels(e.target.value)
          }
        />
      </div>
      <p className="gitcfg-auto-hint">
        The address starts as the one this page is open on. Change it to the one
        the <em>runner</em> reaches this gateway on — for a runner on the same
        Docker host that is usually <code>http://localhost:</code> plus the
        published port.
        <br />
        Labels route the job. GitHub adds <code>self-hosted</code> and the
        runner&apos;s OS and architecture by itself; the rest are yours.{" "}
        <strong>
          If the runner already exists, type the labels it already carries
        </strong>{" "}
        — GitHub → Settings → Actions → Runners lists them against each one. A
        mismatch queues the job for ever with no error, which is the one failure
        here nobody diagnoses. Two gateways need two different labels, so each
        job lands on the machine that can reach the right one.
      </p>
      <div className="gitcfg-cred-actions">
        {unsaved ? (
          <span className="gitcfg-proj-off">Not saved yet</span>
        ) : null}
        <Button disabled={saving} onClick={() => onSave()}>
          Save
        </Button>
      </div>

      <h4 className="gitcfg-step">4 · A runner on the machine</h4>
      {seenAt > 0 ? (
        <p className="gitcfg-auto-hint">
          <span className="gitcfg-proj-ok">Done</span> — a runner called this
          gateway at {formatWhen(seenAt)} ({data.runnerSeen.kind}) and its token
          was accepted. Nothing to install.
        </p>
      ) : (
        <p className="gitcfg-auto-hint">
          No runner has called this gateway since it started, so if one exists
          it has not been pointed here yet. One runner serves every gateway and
          repository it can reach, so this is once per machine, not once per
          project — if the machine already has one, skip to step 5.
        </p>
      )}
      {seenAt > 0 && !showInstall ? (
        <Button colorClass="secondary" onClick={() => setShowInstall(true)}>
          Set up another runner anyway
        </Button>
      ) : (
        <>
          <div className="gitcfg-cred-row">
            <Radio
              name="runner-scope"
              value={scope}
              radios={[
                {
                  label:
                    "The whole organisation — one runner serves every repository in it",
                  value: "org",
                },
                {
                  label: "This repository only",
                  value: "repo",
                },
              ]}
              onChange={(_e: unknown, value: string) =>
                setScope(value as "repo" | "org")
              }
            />
          </div>
          <p className="gitcfg-auto-hint">
            A runner registers at exactly one scope and cannot be moved without
            re-registering, so this is the decision to get right. The
            registration token comes from{" "}
            <code>
              {(scope === "org" ? data.orgUrl : data.repoUrl) ||
                "the repository's (or organisation's)"}
              /settings/actions/runners/new
            </code>{" "}
            and expires in an hour.
          </p>
          <OsTabs
            os={os}
            setOs={setOs}
            snippets={installSnippets}
            label="Install the runner"
          />
        </>
      )}

      <h4 className="gitcfg-step">5 · A workflow that calls this gateway</h4>
      {!data.workflows?.detectable ? (
        <p className="gitcfg-auto-hint">
          {chosen
            ? "This project is not a repository on this gateway, so its workflows cannot be checked from here."
            : "Choose a project above to see what its repository already has."}
        </p>
      ) : wired.length > 0 ? (
        <p className="gitcfg-auto-hint">
          <span className="gitcfg-proj-ok">Done</span> —{" "}
          <code>{wired.map((w) => w.path).join(", ")}</code> already calls a
          gateway through this module. Add the upload step to it if you need
          another gateway; do not add a second workflow.
        </p>
      ) : wf.length > 0 ? (
        <p className="gitcfg-auto-hint">
          This repository has {wf.length}{" "}
          {wf.length === 1 ? "workflow" : "workflows"} (
          <code>{wf.map((w) => w.path).join(", ")}</code>) and none of them
          calls this gateway. Add the one step from the example below to
          whichever already deploys this project — two workflows deploying the
          same project will fight.
        </p>
      ) : (
        <p className="gitcfg-auto-hint">
          This repository has no workflows yet, so nothing will call the gateway
          until one is added.
        </p>
      )}
      <p className="gitcfg-auto-hint">
        Nothing happens until a workflow calls the gateway — everything above
        only makes the gateway willing to answer.
      </p>
      <Button
        colorClass="secondary"
        onClick={() => setShowWorkflow(!showWorkflow)}
      >
        {showWorkflow ? "Hide the example" : "Show an example workflow"}
      </Button>
      {showWorkflow ? (
        <>
          <Snippet label={data.workflowPath} text={data.workflowYaml} />
          {!release ? (
            <>
              <p className="gitcfg-auto-hint">
                For a repository with no workflow of its own, the gateway can
                commit this one: it already has push rights, and it refuses to
                overwrite a different workflow that is already there.
              </p>
              <Button
                disabled={
                  committing || !gatewayUrl || !chosen || !data.hasRemote
                }
                onClick={async () => {
                  try {
                    const r = await commitWorkflow({
                      project: data.project,
                    }).unwrap();
                    if (r.unchanged) toasts.notifySuccess("Already committed");
                    else if (r.pushed)
                      toasts.notifySuccess("Committed and pushed");
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
            </>
          ) : null}
        </>
      ) : null}

      <h4 className="gitcfg-step">Check it before you rely on it</h4>
      <p className="gitcfg-auto-hint">
        {release
          ? "From the runner machine, with the token in place of the placeholder. It sends no zip, so nothing is installed: 400 “no release zip” means the address and token are right."
          : "From the runner machine, with the token in place of the placeholder. A success means the workflow will work."}
      </p>
      <OsTabs
        os={os}
        setOs={setOs}
        snippets={testSnippets}
        label="Test from the runner machine"
      />
    </>
  );
}
