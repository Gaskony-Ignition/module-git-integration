import React from "react";
import { Button, Loading, useToastNotifications } from "../../webui";
// Label-painting wrappers — the platform inputs render `label` into an invisible notch legend.
import { SelectInput, TextInput } from "./fields";
import {
  Delivery,
  ProjectStatus,
  useGetCredentialsQuery,
  useGetProjectsQuery,
  useInitProjectMutation,
  useSaveDeliveryMutation,
  useSetProjectImagesMutation,
  useSnapshotProjectImagesMutation,
  useSetProjectRemoteMutation,
  useSetProjectCredentialMutation,
  useSyncNowMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";
import SettingsDrawer from "./SettingsDrawer";

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
  const toasts = useToastNotifications();

  const [target, setTarget] = React.useState<ProjectStatus | null>(null);
  const [open, setOpen] = React.useState(false);
  const [version, setVersion] = React.useState(false);
  const [url, setUrl] = React.useState("");
  const [credId, setCredId] = React.useState("");
  const [imagePrefix, setImagePrefix] = React.useState("");
  const [delivery, setDelivery] = React.useState<Delivery>("off");
  const [branch, setBranch] = React.useState("");
  const [every, setEvery] = React.useState("300");
  const [user, setUser] = React.useState("");

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
    setDelivery(p.delivery);
    setBranch(p.syncBranch || p.branch || "");
    setEvery(String(p.syncIntervalSeconds));
    setUser(p.syncUser);
    setOpen(true);
  };

  const hasRemote = !!target?.remoteUrl || (version && url.trim() !== "");

  const submit = async () => {
    if (!target) return;
    const chosen = credentials.find((c) => String(c.id) === credId);

    // Repository first: a delivery that pulls needs the remote in place.
    if (!target.versioned && version) {
      await init({
        project: target.name,
        url: url.trim() || undefined,
        sshKeyId: chosen?.type === "SSH" ? chosen.id : undefined,
        httpsCredentialId: chosen?.type === "HTTPS" ? chosen.id : undefined,
      }).unwrap();
    } else if (target.versioned) {
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
      delivery,
      branch: branch.trim(),
      intervalSeconds: Number(every) || 300,
      ignitionUser: user.trim(),
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
          <h3>Projects</h3>
          <p>
            Each project&apos;s repository, and how changes reach it. Delivery
            is Off until you choose one.
          </p>
        </div>
      </div>

      {isFetching && !data ? (
        <Loading isLoading={true} />
      ) : projects.length === 0 ? (
        <p className="gitcfg-empty">No projects on this gateway.</p>
      ) : (
        <div className="gitcfg-table-scroll">
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
                  <td>{p.branch || "—"}</td>
                  <td className="gitcfg-meta gitcfg-proj-remote">
                    {p.remoteUrl
                      ? p.remoteUrl
                      : p.versioned
                      ? "Local only"
                      : "—"}
                  </td>
                  <td>{deliveryCell(p)}</td>
                  <td>{state(p)}</td>
                  <td className="gitcfg-table-act">
                    <Button
                      colorClass="secondary"
                      onClick={() => openTarget(p)}
                    >
                      Edit
                    </Button>
                    {isSync(p.delivery) ? (
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
        </div>
      )}

      <SettingsDrawer
        open={open}
        title={target ? target.name : ""}
        onClose={() => setOpen(false)}
        onSave={submit}
        errorTitle={target ? `Could not save ${target.name}` : undefined}
      >
        {target ? (
          <>
            <h4>Repository</h4>
            {!target.versioned ? (
              <label className="gitcfg-check">
                <input
                  type="checkbox"
                  checked={version}
                  onChange={(e) => setVersion(e.target.checked)}
                />
                <span>Put this project under version control</span>
              </label>
            ) : null}
            {version ? (
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
            <SelectInput
              label="How changes reach this project"
              value={delivery}
              values={(Object.keys(DELIVERY_LABEL) as Delivery[])
                // Without a remote only Off and a runner release are possible.
                .filter((d) => hasRemote || !needsRemote(d) || d === delivery)
                .map((d) => ({ label: DELIVERY_LABEL[d], value: d }))}
              onChange={(e: unknown) =>
                setDelivery((selectValue(e) as Delivery) || "off")
              }
            />
            {!hasRemote ? (
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
    </div>
  );
};

export default Projects;
