import React from "react";
import { Button, Loading, useToastNotifications } from "../../webui";
// Label-painting wrappers — the platform inputs render `label` into an invisible notch legend.
import { TextInput } from "./fields";
import {
  SyncSetting,
  useClearAutomationLogMutation,
  useGetAutomationQuery,
  useGetProjectsQuery,
  useSaveSyncMutation,
  useSyncNowMutation,
} from "./GitConfig.service";
import Runner from "./Runner";
import AutomationHelp from "./AutomationHelp";
import { errorToast } from "./errors";

// Both tabs pull a project's remote down onto this gateway — Scheduled sync on a timer,
// the Actions runner the moment a branch moves. Pushing is a Designer action. Help carries the
// one decision the other two cannot make for you: which way round to point the automation.
type Section = "sync" | "runner" | "help";

const Automation = () => {
  const { data, isFetching } = useGetAutomationQuery(undefined, {
    pollingInterval: 10000,
  });
  const { data: projectData } = useGetProjectsQuery();
  const [clearLog] = useClearAutomationLogMutation();
  const [saveSync, { isLoading: savingSync }] = useSaveSyncMutation();
  const [syncNow, { isLoading: syncing }] = useSyncNowMutation();
  const toasts = useToastNotifications();

  const [section, setSection] = React.useState<Section>("sync");
  const [syncDraft, setSyncDraft] = React.useState<SyncSetting | null>(null);

  const projects = projectData?.projects ?? [];
  const versioned = projects.filter((p) => p.versioned);
  const syncs = data?.syncs ?? [];

  const onSaveSync = () => {
    if (!syncDraft) return;
    saveSync(syncDraft)
      .unwrap()
      .then(() => {
        toasts.notifySuccess(`Sync saved for ${syncDraft.project}`);
        setSyncDraft(null);
      })
      .catch(errorToast(toasts, "Could not save the sync settings"));
  };

  const onSyncNow = (project: string) => {
    syncNow({ project })
      .unwrap()
      .then((r) => toasts.notifySuccess(`${project}: ${r.result}`))
      .catch(errorToast(toasts, "Sync failed"));
  };

  if (isFetching && !data) {
    return <Loading isLoading={true} />;
  }

  const stats = data?.stats;

  return (
    <div>
      <div className="gitcfg-page-head">
        <div>
          <h3>Automation: pulling changes in</h3>
          <p>
            Both tabs bring changes onto this gateway. Scheduled sync pulls a
            project&apos;s branch on a timer. The Actions runner lets a GitHub
            workflow install a release, or pull the branch the moment it moves.
            Nothing on this page pushes or sends anything out: commits and
            pushes happen in the Designer, and gateway config is pushed from the
            Remote Sync button above.
          </p>
        </div>
      </div>

      <div className="gitcfg-subtabs" role="tablist">
        {(
          [
            ["sync", "Scheduled sync"],
            ["runner", "Actions runner"],
            ["help", "Which should I use?"],
          ] as [Section, string][]
        ).map(([key, label]) => (
          <button
            key={key}
            role="tab"
            aria-selected={section === key}
            className={section === key ? "is-active" : ""}
            onClick={() => setSection(key)}
          >
            {label}
          </button>
        ))}
      </div>

      {section === "sync" ? (
        <>
          <p className="gitcfg-hint">
            The gateway fetches each enabled repository on a timer and
            fast-forwards it when the tracked branch has moved, then requests a
            project scan. It refuses when the working tree has local changes —
            someone is editing — rather than discarding them. There is no
            inbound webhook: GitHub cannot usually reach a gateway.
          </p>
          {versioned.length === 0 ? (
            <p className="gitcfg-empty">
              No versioned projects. Set one up on the Projects tab first.
            </p>
          ) : (
            <div className="gitcfg-table-scroll">
              <table className="gitcfg-table">
                <thead>
                  <tr>
                    <th>Project</th>
                    <th>Sync</th>
                    <th>Branch</th>
                    <th>Every</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {versioned.map((p) => {
                    const s = syncs.find((x) => x.project === p.name);
                    return (
                      <tr key={p.name}>
                        <td>
                          <span className="gitcfg-proj-name">{p.name}</span>
                        </td>
                        <td>
                          {s?.enabled ? (
                            <span className="gitcfg-ok">On</span>
                          ) : (
                            <span className="gitcfg-off">Off</span>
                          )}
                        </td>
                        <td>{s?.branch || p.branch || "—"}</td>
                        <td>{s ? `${s.intervalSeconds}s` : "—"}</td>
                        <td className="gitcfg-table-act">
                          <Button
                            colorClass="secondary"
                            onClick={() =>
                              setSyncDraft(
                                s ?? {
                                  project: p.name,
                                  enabled: true,
                                  remoteName: p.remoteName || "origin",
                                  branch: p.branch || "",
                                  intervalSeconds: 300,
                                  ignitionUser: "",
                                }
                              )
                            }
                          >
                            {s ? "Edit" : "Set up"}
                          </Button>
                          {s?.enabled ? (
                            <Button
                              colorClass="secondary"
                              disabled={syncing}
                              onClick={() => onSyncNow(p.name)}
                            >
                              Sync now
                            </Button>
                          ) : null}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}

          {syncDraft ? (
            <div className="gitcfg-cred-form">
              <h4>Scheduled sync for {syncDraft.project}</h4>
              <label className="gitcfg-check">
                <input
                  type="checkbox"
                  checked={syncDraft.enabled}
                  onChange={(e) =>
                    setSyncDraft({ ...syncDraft, enabled: e.target.checked })
                  }
                />
                <span>Fetch and fast-forward on a schedule</span>
              </label>
              <div className="gitcfg-auto-pair">
                <TextInput
                  label="Remote"
                  value={syncDraft.remoteName}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setSyncDraft({ ...syncDraft, remoteName: e.target.value })
                  }
                />
                <TextInput
                  label="Branch — the project switches to it; empty follows whatever is checked out"
                  value={syncDraft.branch}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setSyncDraft({ ...syncDraft, branch: e.target.value })
                  }
                />
              </div>
              <TextInput
                label="Interval in seconds — minimum 30"
                value={String(syncDraft.intervalSeconds)}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setSyncDraft({
                    ...syncDraft,
                    intervalSeconds: Number(e.target.value) || 300,
                  })
                }
              />
              <p className="gitcfg-hint">
                Sync runs unattended, so it authenticates with the stored
                credential of a named user rather than borrowing whoever is in a
                Designer. Left empty it uses yours.
              </p>
              <TextInput
                label="Authenticate as"
                placeholder="your username"
                value={syncDraft.ignitionUser}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setSyncDraft({ ...syncDraft, ignitionUser: e.target.value })
                }
              />
              <div className="gitcfg-actions is-end">
                <Button
                  colorClass="secondary"
                  onClick={() => setSyncDraft(null)}
                >
                  Cancel
                </Button>
                <Button
                  colorClass="primary"
                  disabled={savingSync}
                  onClick={onSaveSync}
                >
                  {savingSync ? "Saving…" : "Save"}
                </Button>
              </div>
            </div>
          ) : null}
        </>
      ) : null}

      {section === "runner" ? (
        <Runner onHelp={() => setSection("help")} />
      ) : null}

      {section === "help" ? <AutomationHelp /> : null}

      {/* The log belongs to the two working tabs; on Help it would just push the tables off. */}
      <div className="gitcfg-auto-log" hidden={section === "help"}>
        <div className="gitcfg-page-head">
          <div>
            <h4>
              Event log
              {stats ? (
                <span className="gitcfg-auto-stats">
                  {" "}
                  · {stats.fired} {stats.fired === 1 ? "event" : "events"} ·{" "}
                  <span className={stats.failures > 0 ? "is-bad" : ""}>
                    {stats.failures} failed
                  </span>
                </span>
              ) : null}
            </h4>
            <p>
              The last 50 git events since the gateway started: commits, pushes,
              pulls, config auto-commits, every scheduled or runner sync, and
              every release a runner installed. An unattended sync that refused
              or failed is reported here, with its reason, and nowhere else.
            </p>
          </div>
          <div className="gitcfg-actions">
            <Button
              colorClass="secondary"
              onClick={() =>
                clearLog()
                  .unwrap()
                  .catch(errorToast(toasts, "Could not clear the log"))
              }
            >
              Clear
            </Button>
          </div>
        </div>
        {(data?.log ?? []).length === 0 ? (
          <p className="gitcfg-empty">
            Nothing yet. Commit or push from a Designer, or press Sync now.
          </p>
        ) : (
          <div className="gitcfg-table-scroll">
            <table className="gitcfg-table">
              <thead>
                <tr>
                  <th>When</th>
                  <th>Event</th>
                  <th>Where</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {(data?.log ?? []).map((e, i) => {
                  const details = [
                    e.user,
                    e.commit ? e.commit.slice(0, 7) : "",
                    e.fileCount > 0 ? `${e.fileCount} file(s)` : "",
                    e.remote,
                  ]
                    .filter((v) => v)
                    .join(" · ");
                  return (
                    <tr key={`${e.timestamp}-${i}`}>
                      <td className="gitcfg-meta">
                        {e.timestamp.replace("T", " ").replace(/\..*$/, "")}
                      </td>
                      <td>
                        <span
                          className={
                            e.outcome === "failure" ? "gitcfg-err" : "gitcfg-ok"
                          }
                        >
                          {e.type}
                        </span>
                        {e.message ? (
                          <span className="gitcfg-proj-title">{e.message}</span>
                        ) : null}
                      </td>
                      <td>
                        {e.scope === "config"
                          ? "gateway config"
                          : `${e.project}${e.branch ? ` · ${e.branch}` : ""}`}
                      </td>
                      <td className="gitcfg-meta gitcfg-proj-remote">
                        {details}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
};

export default Automation;
