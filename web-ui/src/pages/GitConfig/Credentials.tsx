import React from "react";
import { Button, Loading, Radio, useToastNotifications } from "../../webui";
// Label-painting wrappers — the platform inputs render `label` into an invisible notch legend.
import { SelectInput, TextArea, TextInput } from "./fields";
import {
  AddCredentialReq,
  CredentialOption,
  useAddCredentialMutation,
  useCheckCredentialMutation,
  useGetCredentialsQuery,
  useGetRunnerQuery,
  useGetSecretProvidersQuery,
  useRemoveCredentialMutation,
  useSaveRunnerMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";
import SettingsDrawer from "./SettingsDrawer";

// Credentials are per Ignition user and are what a project repository authenticates with. They
// could only be created from the Designer's setup wizard, which puts them behind the thing they
// are needed for: you cannot clone a project until the key exists, and you could not make the key
// without opening a Designer. This tab is the same records, reachable before that point.
type Kind = "SSH" | "HTTPS";
type Mode = "inline" | "reference";

const Credentials = () => {
  // Polls only while a check the gateway queued (after an add) has not reported yet.
  const [pending, setPending] = React.useState(false);
  const { data, isFetching } = useGetCredentialsQuery(undefined, {
    pollingInterval: pending ? 3000 : 0,
  });
  const [check, { isLoading: checking }] = useCheckCredentialMutation();
  const { data: providers } = useGetSecretProvidersQuery();
  const [add] = useAddCredentialMutation();
  const [remove] = useRemoveCredentialMutation();
  const { data: runner } = useGetRunnerQuery();
  const [saveRunner] = useSaveRunnerMutation();
  const toasts = useToastNotifications();

  // Runner access: the gateway-wide switch and token the runner authenticates with.
  const [runnerOpen, setRunnerOpen] = React.useState(false);
  const [accept, setAccept] = React.useState(false);
  const [newToken, setNewToken] = React.useState(false);
  // Shown once after it is generated; the gateway cannot return it again.
  const [issued, setIssued] = React.useState<string | null>(null);
  const openRunner = () => {
    setAccept(runner?.enabled ?? false);
    setNewToken(!runner?.hasToken);
    setRunnerOpen(true);
  };
  const submitRunner = async () => {
    const res = await saveRunner({
      enabled: accept,
      generateToken: newToken,
    }).unwrap();
    if (res.token) setIssued(res.token);
    toasts.notifySuccess("Runner access saved");
  };

  const [open, setOpen] = React.useState(false);
  // Set when the drawer edits an existing credential: same id, so its projects stay linked.
  const [editId, setEditId] = React.useState<number | null>(null);
  const [kind, setKind] = React.useState<Kind>("SSH");
  const [mode, setMode] = React.useState<Mode>("inline");
  const [name, setName] = React.useState("");
  const [key, setKey] = React.useState("");
  const [host, setHost] = React.useState("");
  const [username, setUsername] = React.useState("");
  const [password, setPassword] = React.useState("");
  const [providerName, setProviderName] = React.useState("");
  const [secretName, setSecretName] = React.useState("");

  const providerList = providers?.providers ?? [];
  const secrets =
    providerList.find((p) => p.name === providerName)?.secrets ?? [];

  const reset = () => {
    setName("");
    setKey("");
    setHost("");
    setUsername("");
    setPassword("");
    setProviderName("");
    setSecretName("");
  };

  const openAdd = () => {
    reset();
    setEditId(null);
    setMode("inline");
    setOpen(true);
  };
  const openEdit = (c: CredentialOption) => {
    reset();
    setEditId(c.id);
    setKind(c.type);
    setMode("inline");
    setName(c.name ?? "");
    setHost(c.host ?? "");
    setUsername(c.username ?? "");
    setOpen(true);
  };

  // Editing, a blank typed secret keeps the stored one.
  const secretReady =
    mode === "inline"
      ? editId !== null || (kind === "SSH" ? key.trim() : password) !== ""
      : providerName !== "" && secretName !== "";
  const ready =
    (kind === "SSH" ? name.trim() !== "" : host.trim() !== "") && secretReady;

  const save = async () => {
    let body: AddCredentialReq;
    if (kind === "SSH") {
      body =
        mode === "inline"
          ? { type: "SSH", name: name.trim(), mode: "inline", key }
          : {
              type: "SSH",
              name: name.trim(),
              mode: "reference",
              providerName,
              secretName,
            };
    } else {
      body =
        mode === "inline"
          ? {
              type: "HTTPS",
              host: host.trim(),
              username: username.trim(),
              mode: "inline",
              password,
            }
          : {
              type: "HTTPS",
              host: host.trim(),
              username: username.trim(),
              mode: "reference",
              providerName,
              secretName,
            };
    }
    await add(editId !== null ? { ...body, id: editId } : body).unwrap();
    // Cleared the moment it is stored, so the closed drawer holds no copy of the secret.
    reset();
    toasts.notifySuccess("Credential saved");
  };

  const del = (type: Kind, id: number, label: string) => {
    remove({ type, id })
      .unwrap()
      .then(() => toasts.notifySuccess(`Removed ${label}`))
      .catch(errorToast(toasts, "Could not remove the credential"));
  };

  const rows = data?.credentials ?? [];
  React.useEffect(
    () => setPending((data?.credentials ?? []).some((c) => c.check === null)),
    [data]
  );

  return (
    <div>
      <div className="gitcfg-page-head">
        <div>
          <h3>Credentials</h3>
          <p>
            What this gateway authenticates with: to repositories, and from a
            runner.
          </p>
        </div>
      </div>

      <div className="gitcfg-section-head">
        <h4>Repositories</h4>
        <div className="gitcfg-actions">
          <Button colorClass="primary" onClick={openAdd}>
            Add credential
          </Button>
        </div>
      </div>

      <SettingsDrawer
        open={open}
        title={editId !== null ? "Edit credential" : "Add credential"}
        onClose={() => setOpen(false)}
        onSave={save}
        saveDisabled={!ready}
        errorTitle="Could not save the credential"
      >
        <>
          {/*
            Radio is a radio GROUP: it takes a `radios` array and maps over it. Used as a single
            radio with `label`/`checked` it reads `radios.map` on undefined and takes the whole
            page down with an Application Error.
          */}
          {editId === null ? (
            <div className="gitcfg-cred-row">
              <Radio
                name="cred-kind"
                value={kind}
                radios={[
                  { label: "SSH key", value: "SSH" },
                  { label: "HTTPS username and token", value: "HTTPS" },
                ]}
                onChange={(_e: unknown, value: string) =>
                  setKind(value as "SSH" | "HTTPS")
                }
              />
            </div>
          ) : null}

          {kind === "SSH" ? (
            <TextInput
              label="Key name"
              value={name}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setName(e.target.value)
              }
            />
          ) : (
            <>
              <TextInput
                label="Host"
                placeholder="github.com"
                value={host}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setHost(e.target.value)
                }
              />
              <TextInput
                label="Username"
                value={username}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setUsername(e.target.value)
                }
              />
            </>
          )}

          <div className="gitcfg-cred-row">
            <Radio
              name="cred-mode"
              value={mode}
              radios={[
                { label: "Type the secret", value: "inline" },
                { label: "Use a stored secret", value: "reference" },
              ]}
              onChange={(_e: unknown, value: string) =>
                setMode(value as "inline" | "reference")
              }
            />
          </div>

          {mode === "inline" ? (
            kind === "SSH" ? (
              <TextArea
                label={
                  editId !== null
                    ? "New private key — blank keeps the current one"
                    : "Private key"
                }
                rows={8}
                value={key}
                onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) =>
                  setKey(e.target.value)
                }
              />
            ) : (
              <TextInput
                label={
                  editId !== null
                    ? "New password or token — blank keeps the current one"
                    : "Password or token"
                }
                type="password"
                value={password}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setPassword(e.target.value)
                }
              />
            )
          ) : (
            <>
              <SelectInput
                label="Secret provider"
                value={providerName}
                values={providerList.map((p) => ({
                  label: p.name,
                  value: p.name,
                }))}
                onChange={(e: unknown) => {
                  setProviderName(selectValue(e));
                  setSecretName("");
                }}
              />
              <SelectInput
                label="Secret"
                value={secretName}
                values={secrets.map((sname) => ({
                  label: sname,
                  value: sname,
                }))}
                onChange={(e: unknown) => setSecretName(selectValue(e))}
              />
            </>
          )}
        </>
      </SettingsDrawer>

      {isFetching ? (
        <Loading isLoading={true} />
      ) : rows.length === 0 ? (
        <p className="gitcfg-empty">
          None yet. Needed only when a project pulls from a remote.
        </p>
      ) : (
        <div className="gitcfg-table-scroll">
          <table className="gitcfg-table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Credential</th>
                <th>Expires</th>
                <th>Reaches</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={`${c.type}-${c.id}`}>
                  <td>{c.type}</td>
                  <td>
                    {c.label}
                    {c.check?.account &&
                    !c.label.endsWith(`— ${c.check.account}`) ? (
                      <span className="gitcfg-proj-title">
                        {c.check.account}
                      </span>
                    ) : null}
                  </td>
                  <td>{expiry(c)}</td>
                  <td>{reach(c)}</td>
                  <td className="gitcfg-table-act">
                    <Button
                      colorClass="secondary"
                      disabled={checking}
                      onClick={() =>
                        check({ type: c.type, id: c.id })
                          .unwrap()
                          .catch(errorToast(toasts, "Could not check it"))
                      }
                    >
                      Check
                    </Button>
                    <Button colorClass="secondary" onClick={() => openEdit(c)}>
                      Edit
                    </Button>
                    <Button
                      colorClass="secondary"
                      onClick={() => del(c.type, c.id, c.label)}
                    >
                      Remove
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="gitcfg-section-head">
        <h4>Runner access</h4>
        <div className="gitcfg-actions">
          <Button colorClass="secondary" onClick={openRunner}>
            Edit
          </Button>
        </div>
      </div>
      <p className="gitcfg-hint">
        {runner?.enabled ? (
          <span className="gitcfg-ok">Accepting runner deliveries</span>
        ) : (
          <span className="gitcfg-off">Off</span>
        )}
        {" · "}
        {runner?.hasToken ? "token set" : "no token"}
      </p>
      {issued ? (
        <div className="gitcfg-token-once">
          <strong>Copy this token now — it is not shown again.</strong>
          <Snippet text={issued} />
        </div>
      ) : null}

      <SettingsDrawer
        open={runnerOpen}
        title="Runner access"
        onClose={() => setRunnerOpen(false)}
        onSave={submitRunner}
        errorTitle="Could not save runner access"
      >
        <label className="gitcfg-check">
          <input
            type="checkbox"
            checked={accept}
            onChange={(e) => setAccept(e.target.checked)}
          />
          <span>Accept deliveries from a runner</span>
        </label>
        <label className="gitcfg-check">
          <input
            type="checkbox"
            checked={newToken}
            onChange={(e) => setNewToken(e.target.checked)}
          />
          <span>
            {runner?.hasToken
              ? "Generate a new token — the current one stops working"
              : "Generate a token"}
          </span>
        </label>
        <p className="gitcfg-hint">
          Each project still needs a runner delivery on the Projects tab.
        </p>
      </SettingsDrawer>
    </div>
  );
};

export default Credentials;

const DAY = 86400000;

/** DD/MM/YYYY from an ISO date. */
const ddmmyyyy = (iso: string) => iso.split("-").reverse().join("/");

// A GitHub token's expiry. Amber within 14 days, matching the Projects tab's warning.
function expiry(c: CredentialOption) {
  const k = c.check;
  if (!k) return <span className="gitcfg-off">Checking…</span>;
  if (k.rejected)
    return <span className="gitcfg-err">Rejected — expired or revoked</span>;
  if (!k.expires) return <span className="gitcfg-off">—</span>;
  if (k.expires === "never") return "No expiry";
  const days = Math.ceil(
    (new Date(`${k.expires}T00:00:00`).getTime() - Date.now()) / DAY
  );
  const text = `${ddmmyyyy(k.expires)} (${
    days <= 0 ? "expired" : `${days} ${days === 1 ? "day" : "days"}`
  })`;
  if (days <= 0) return <span className="gitcfg-err">{text}</span>;
  if (days <= 14) return <span className="gitcfg-dirty">{text}</span>;
  return text;
}

// Each remote using the credential, and what it may do there.
function reach(c: CredentialOption) {
  const k = c.check;
  if (!k) return null;
  if (k.reach.length === 0) return <span className="gitcfg-off">Unused</span>;
  return k.reach.map((r) => (
    <span
      key={r.target}
      className={`gitcfg-reach ${r.read ? "" : "gitcfg-err"}`}
      title={r.error ?? undefined}
    >
      {r.target}: {r.push ? "read, push" : r.read ? "read" : "no access"}
    </span>
  ));
}

/** The token, with a copy button: retyping one is how it breaks. */
function Snippet({ text }: { text: string }) {
  const toasts = useToastNotifications();
  return (
    <div className="gitcfg-snippet">
      <div className="gitcfg-snippet-bar">
        <span>Runner token</span>
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
