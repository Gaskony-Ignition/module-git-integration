import React from "react";
import { Button, Loading, useToastNotifications } from "../../webui";
// Label-painting wrappers — the platform inputs render `label` into an invisible notch legend.
import { SelectInput, TextInput } from "./fields";
import {
  Delivery,
  ProjectStatus,
  useGetCredentialsQuery,
  useGetProjectsQuery,
  useGetStatusQuery,
  useInitProjectMutation,
  useSaveDeliveryMutation,
  useSetProjectImagesMutation,
  useSnapshotProjectImagesMutation,
  useSetProjectRemoteMutation,
  useSetProjectCredentialMutation,
  useSyncNowMutation,
  useGetDefinitionQuery,
  useRenameMasterMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";
import SettingsDrawer from "./SettingsDrawer";
import BranchesView from "./BranchesView";
import Definitions, {
  OverrideTag,
  saveToParam,
  useEditing,
  useModes,
} from "./ModeScope";
import ScrollBox, { RowCount } from "./ScrollBox";

const DELIVERY_LABEL: Record<Delivery, string> = {
  off: "Off",
  "runner-release": "Runner — release",
  "runner-repo": "Runner — repo updates",
  "sync-pull": "Sync — pull",
  "sync-replace": "Sync — replace",
};

// Every delivery but Off and a runner release pulls from the project's own remote.
const needsRemote = (d: Delivery) => d !== "off" && d !== "runner-release";
const isSync = (d: Delivery) => d === "sync-pull" || d === "sync-replace";
const NONE = "__none";

// One row per project: its git state, and how changes reach it. Unversioned projects are listed
// too — "not in git" and "not on this gateway" look identical if only repositories are shown.
const Projects = () => {
  const { data, isFetching } = useGetProjectsQuery();
  const { data: creds } = useGetCredentialsQuery();
  const [init] = useInitProjectMutation();
  const [setRemote] = useSetProjectRemoteMutation();
  const [setImages] = useSetProjectImagesMutation();
  const [setCredential] = useSetProjectCredentialMutation();
  const [saveDelivery] = useSaveDeliveryMutation();
  const [snapshotImages, { isLoading: snapshotting }] =
    useSnapshotProjectImagesMutation();
  const [syncNow, { isLoading: syncing }] = useSyncNowMutation();
  const [renameMaster, { isLoading: renaming }] = useRenameMasterMutation();
  const toasts = useToastNotifications();

  const [target, setTarget] = React.useState<ProjectStatus | null>(null);
  const [open, setOpen] = React.useState(false);
  // The project whose Branches drawer is open (the row's Branches button).
  const [branchesOf, setBranchesOf] = React.useState<string | null>(null);
  const [branchesOpen, setBranchesOpen] = React.useState(false);
  const [version, setVersion] = React.useState(false);
  const [url, setUrl] = React.useState("");
  const [credId, setCredId] = React.useState("");
  const [imagePrefix, setImagePrefix] = React.useState("");
  const [delivery, setDelivery] = React.useState<Delivery>("off");
  const [branch, setBranch] = React.useState("");
  const [every, setEvery] = React.useState("300");
  const [user, setUser] = React.useState("");
  const [initialDelivery, setInitialDelivery] = React.useState<Delivery>("off");

  // Combined: one gateway repository, so the remote, branch and pulls are the gateway's. A
  // project keeps only Off or a runner release, which replaces this one project.
  const combined = data?.layout === "combined";
  const modes = useModes();
  const [editing, setEditing] = useEditing(open, target?.syncFrom);
  // Delivery definitions are per mode only in Separate; in Combined a project's delivery is the
  // runner record, overridden from Runner access.
  const perMode = modes.length > 0 && !combined;
  const syncRef =
    target && target.syncFrom
      ? { type: "sync" as const, project: target.name }
      : undefined;
  const { data: def } = useGetDefinitionQuery(
    { type: "sync", project: target?.name ?? "", collection: editing },
    { skip: !open || !perMode || !syncRef }
  );
  React.useEffect(() => {
    if (!open || !def || def.collection !== editing) return;
    const d = def as unknown as ProjectStatus;
    setDelivery(d.delivery);
    setBranch(d.syncBranch || target?.branch || "");
    setEvery(String(d.syncIntervalSeconds));
    setUser(d.syncUser);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, def, editing]);
  const defs = {
    record: syncRef,
    defined: def?.defined ?? target?.syncDefined ?? [],
    editing,
    onEditing: setEditing,
  };
  const saveTo = perMode ? saveToParam(modes, editing) : undefined;
  const { data: status } = useGetStatusQuery();
  const refused = status?.policy?.refused ?? {};
  // "sync-pull" → pull, "runner-release" → runner: the policy's four kinds.
  const kindOf = (d: Delivery) =>
    d.startsWith("runner") ? "runner" : d.replace("sync-", "");

  const credentials = creds?.credentials ?? [];
  const projects = data?.projects ?? [];
  const imageFolders = data?.imageFolders ?? [];

  // Only `open` gates visibility; the fields keep their values so the closing slide has
  // something to animate.
  const openTarget = (p: ProjectStatus) => {
    setTarget(p);
    setVersion(p.versioned);
    setUrl(p.remoteUrl || "");
    setCredId("");
    setImagePrefix(p.imagePrefix || "");
    // A pulling delivery is kept but inactive in Combined; the drawer offers Off or release.
    const shown =
      combined && p.delivery !== "runner-release" ? "off" : p.delivery;
    setDelivery(shown);
    setInitialDelivery(shown);
    setBranch(p.syncBranch || p.branch || "");
    setEvery(String(p.syncIntervalSeconds));
    setUser(p.syncUser);
    setOpen(true);
  };

  const hasRemote = !!target?.remoteUrl || (version && url.trim() !== "");

  const submit = async () => {
    if (!target) return;
    const chosen = credentials.find((c) => String(c.id) === credId);

    const deliveryFields = {
      delivery,
      branch: branch.trim(),
      intervalSeconds: Number(every) || 300,
      ignitionUser: user.trim(),
    };

    if (combined) {
      if (imagePrefix !== (target.imagePrefix || "")) {
        await setImages({ project: target.name, imagePrefix }).unwrap();
      }
      // Saved only when changed, so an inactive pulling delivery survives a switch back.
      if (delivery !== initialDelivery) {
        await saveDelivery({
          project: target.name,
          delivery,
          saveTo,
        }).unwrap();
      }
      toasts.notifySuccess(`Saved ${target.name}`);
      return;
    }

    // One request: the gateway clones, then sets the delivery, so leaving the page mid-save
    // cannot leave the project linked with Delivery Off.
    if (!target.versioned && version) {
      await init({
        project: target.name,
        url: url.trim() || undefined,
        sshKeyId: chosen?.type === "SSH" ? chosen.id : undefined,
        httpsCredentialId: chosen?.type === "HTTPS" ? chosen.id : undefined,
        ...deliveryFields,
        // Clone the branch the delivery will pull, not the remote's default.
        branch: (needsRemote(delivery) && branch.trim()) || undefined,
        saveTo,
      }).unwrap();
      toasts.notifySuccess(`Saved ${target.name}`);
      return;
    }
    if (target.versioned) {
      if (url.trim() !== "" && url.trim() !== (target.remoteUrl || "")) {
        await setRemote({ project: target.name, url: url.trim() }).unwrap();
      }
      if (imagePrefix !== (target.imagePrefix || "")) {
        await setImages({ project: target.name, imagePrefix }).unwrap();
      }
      if (chosen) {
        await setCredential({
          project: target.name,
          remoteName: target.remoteName || "origin",
          sshKeyId: chosen.type === "SSH" ? chosen.id : 0,
          httpsCredentialId: chosen.type === "HTTPS" ? chosen.id : 0,
        }).unwrap();
      }
    }

    await saveDelivery({
      project: target.name,
      ...deliveryFields,
      saveTo,
    }).unwrap();
    toasts.notifySuccess(`Saved ${target.name}`);
  };

  const state = (p: ProjectStatus) => {
    if (p.error) return <span className="gitcfg-err">{p.error}</span>;
    if (!p.versioned) return <span className="gitcfg-off">Not versioned</span>;
    if (p.changes < 0) return <span className="gitcfg-err">Unreadable</span>;
    if (p.changes === 0) return <span className="gitcfg-ok">Clean</span>;
    return <span className="gitcfg-dirty">{p.changes} uncommitted</span>;
  };

  const deliveryCell = (p: ProjectStatus) => {
    if (combined && p.delivery !== "runner-release") {
      return <span className="gitcfg-off">Gateway delivery</span>;
    }
    if (p.delivery === "off") return <span className="gitcfg-off">Off</span>;
    const label = isSync(p.delivery)
      ? `${DELIVERY_LABEL[p.delivery]} · ${p.syncIntervalSeconds}s`
      : DELIVERY_LABEL[p.delivery];
    // A runner delivery with the runner switched off delivers nothing.
    if (!isSync(p.delivery) && !p.runnerEnabled) {
      return <span className="gitcfg-off">{label} (runner off)</span>;
    }
    return <span className="gitcfg-ok">{label}</span>;
  };

  return (
    <div>
      <div className="gitcfg-page-head">
        <div>
          <h3>
            Projects
            <RowCount n={projects.length} noun="project" />
          </h3>
          {combined ? (
            <p>
              Every project is in the gateway repository (Combined layout). The
              branch, remote and pulls are the gateway&apos;s, set in Configure
              Versioning; a project&apos;s own delivery is Off or a runner
              release.
            </p>
          ) : (
            <p>
              Each project&apos;s repository, and how changes reach it. Delivery
              is Off until you choose one.
            </p>
          )}
        </div>
      </div>

      {isFetching && !data ? (
        <Loading isLoading={true} />
      ) : projects.length === 0 ? (
        <p className="gitcfg-empty">No projects on this gateway.</p>
      ) : (
        <ScrollBox className="gitcfg-table-scroll">
          <table className="gitcfg-table">
            <thead>
              <tr>
                <th>Project</th>
                <th>Branch</th>
                <th>Remote</th>
                <th>Delivery</th>
                <th>State</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {projects.map((p) => (
                <tr key={p.name}>
                  <td>
                    <span className="gitcfg-proj-name">{p.name}</span>
                    {p.title && p.title !== p.name ? (
                      <span className="gitcfg-proj-title">{p.title}</span>
                    ) : null}
                  </td>
                  <td>
                    {p.branch || "—"}
                    {p.ahead ? (
                      <span className="gitcfg-ab">{` ↑${p.ahead}`}</span>
                    ) : null}
                    {p.behind ? (
                      <span className="gitcfg-ab">{` ↓${p.behind}`}</span>
                    ) : null}
                    {p.renameMaster ? (
                      <button
                        type="button"
                        className="gitcfg-link"
                        disabled={renaming || !!refused.BRANCH_SWITCH}
                        onClick={() =>
                          renameMaster({ project: p.name })
                            .unwrap()
                            .then((r) =>
                              toasts.notifySuccess(
                                `${p.name}: local master is now ${r.branch}`
                              )
                            )
                            .catch(
                              errorToast(toasts, "Could not rename the branch")
                            )
                        }
                      >
                        {`Rename to ${p.renameMaster}`}
                      </button>
                    ) : null}
                  </td>
                  <td className="gitcfg-meta gitcfg-proj-remote">
                    {p.remoteUrl
                      ? p.remoteUrl
                      : p.versioned
                      ? "Local only"
                      : "—"}
                  </td>
                  <td>
                    {deliveryCell(p)}{" "}
                    <OverrideTag defined={p.syncDefined} from={p.syncFrom} />
                  </td>
                  <td>
                    {state(p)}
                    {p.credentialIssue ? (
                      <span
                        className={`gitcfg-reach ${
                          p.credentialIssue.startsWith("Credential expires")
                            ? "gitcfg-dirty"
                            : "gitcfg-err"
                        }`}
                      >
                        {p.credentialIssue}
                      </span>
                    ) : null}
                  </td>
                  <td className="gitcfg-table-act">
                    <Button
                      colorClass="secondary"
                      onClick={() => openTarget(p)}
                    >
                      Edit
                    </Button>
                    {p.versioned ? (
                      <Button
                        colorClass="secondary"
                        onClick={() => {
                          setBranchesOf(p.name);
                          setBranchesOpen(true);
                        }}
                      >
                        Branches
                      </Button>
                    ) : null}
                    {isSync(p.delivery) && !combined ? (
                      <Button
                        colorClass="secondary"
                        disabled={syncing}
                        onClick={() =>
                          syncNow({ project: p.name })
                            .unwrap()
                            .then((r) =>
                              toasts.notifySuccess(`${p.name}: ${r.result}`)
                            )
                            .catch(errorToast(toasts, "Sync failed"))
                        }
                      >
                        Sync now
                      </Button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollBox>
      )}

      <SettingsDrawer
        open={open}
        title={target ? target.name : ""}
        onClose={() => setOpen(false)}
        onSave={submit}
        errorTitle={target ? `Could not save ${target.name}` : undefined}
        defs={perMode ? defs : undefined}
      >
        {target ? (
          <>
            <h4>Repository</h4>
            {combined ? (
              <p className="gitcfg-hint">
                Versioned in the gateway repository. Its remote and credential
                are the gateway&apos;s, set in Configure versioning.
              </p>
            ) : null}
            {!target.versioned && !combined ? (
              <label className="gitcfg-check">
                <input
                  type="checkbox"
                  checked={version}
                  onChange={(e) => setVersion(e.target.checked)}
                />
                <span>Put this project under version control</span>
              </label>
            ) : null}
            {version && !combined ? (
              <>
                <TextInput
                  label={
                    target.versioned
                      ? "Remote URL"
                      : "Remote URL — empty for a local-only repository"
                  }
                  placeholder="https://github.com/org/repo.git"
                  value={url}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setUrl(e.target.value)
                  }
                />
                {url.trim() !== "" ? (
                  <SelectInput
                    label="Credential"
                    value={credId}
                    values={credentials.map((c) => ({
                      label: `${c.type} — ${c.label}`,
                      value: String(c.id),
                    }))}
                    onChange={(e: unknown) => setCredId(selectValue(e))}
                  />
                ) : null}
              </>
            ) : null}
            {target.versioned ? (
              <>
                <SelectInput
                  label="Images folder this project versions"
                  // "" reads as no selection to the platform select, so None needs a value.
                  value={imagePrefix || NONE}
                  values={[
                    { label: "None", value: NONE },
                    ...imageFolders.map((f: string) => ({
                      label: f,
                      value: f,
                    })),
                  ]}
                  onChange={(e: unknown) => {
                    const v = selectValue(e);
                    setImagePrefix(v === NONE ? "" : v);
                  }}
                />
                {target.imagePrefix ? (
                  <div className="gitcfg-actions is-end">
                    <Button
                      colorClass="secondary"
                      disabled={snapshotting}
                      onClick={() =>
                        snapshotImages({ project: target.name })
                          .unwrap()
                          .then(() =>
                            toasts.notifySuccess(
                              `Wrote ${target.imagePrefix} into ${target.name}`
                            )
                          )
                          .catch(
                            errorToast(toasts, "Could not snapshot the images")
                          )
                      }
                    >
                      {snapshotting ? "Writing…" : "Snapshot images now"}
                    </Button>
                  </div>
                ) : null}
              </>
            ) : null}

            <h4>Delivery</h4>
            {perMode ? <Definitions {...defs} /> : null}
            <SelectInput
              label="How changes reach this project"
              value={delivery}
              // Without a remote only Off and a runner release are possible; the rest stay
              // listed but disabled so it is clear they exist.
              values={(Object.keys(DELIVERY_LABEL) as Delivery[])
                .filter((d) => !combined || !needsRemote(d))
                .map((d) => {
                  // Refused by the Git policy: listed, disabled, and saying why.
                  const off =
                    d !== "off" && d !== delivery
                      ? refused[`delivery:${kindOf(d)}`]
                      : undefined;
                  return {
                    label: off
                      ? `${DELIVERY_LABEL[d]} (not allowed by policy)`
                      : DELIVERY_LABEL[d],
                    value: d,
                    disabled:
                      (!hasRemote && needsRemote(d) && d !== delivery) || !!off,
                  };
                })}
              onChange={(e: unknown) =>
                setDelivery((selectValue(e) as Delivery) || "off")
              }
            />
            {combined ? (
              <p className="gitcfg-hint">
                Repo updates and sync are set once for the whole gateway in
                Configure versioning › Delivery.
              </p>
            ) : !hasRemote ? (
              <p className="gitcfg-hint">
                Repo updates and sync need a remote.
              </p>
            ) : null}
            {delivery === "sync-replace" ? (
              <p className="gitcfg-hint">
                Replace makes the project match the branch exactly, overwriting
                local edits.
              </p>
            ) : null}
            {needsRemote(delivery) ? (
              <>
                <TextInput
                  label="Branch"
                  value={branch}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setBranch(e.target.value)
                  }
                />
                {isSync(delivery) ? (
                  <TextInput
                    label="Every (seconds, minimum 30)"
                    value={every}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                      setEvery(e.target.value)
                    }
                  />
                ) : null}
                <TextInput
                  label="Authenticate as — whose stored credential pulls; empty is you"
                  value={user}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setUser(e.target.value)
                  }
                />
              </>
            ) : null}
          </>
        ) : null}
      </SettingsDrawer>

      <SettingsDrawer
        open={branchesOpen}
        title={branchesOf ? `${branchesOf} — Branches` : ""}
        onClose={() => setBranchesOpen(false)}
        onSave={async () => undefined}
        large
        readOnly
      >
        {branchesOf ? (
          <BranchesView
            project={branchesOf}
            compact
            onConfigureRemote={() => {
              const p = projects.find((x) => x.name === branchesOf);
              setBranchesOpen(false);
              if (p) openTarget(p);
            }}
          />
        ) : null}
      </SettingsDrawer>
    </div>
  );
};

export default Projects;
