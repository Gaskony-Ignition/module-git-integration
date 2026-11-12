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
  useGetSecretProvidersQuery,
  useRemoveCredentialMutation,
  useGetStatusQuery,
  useGetDefinitionQuery,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";
import SettingsDrawer from "./SettingsDrawer";
import Definitions, {
  OverrideTag,
  saveToParam,
  useEditing,
  useModes,
} from "./ModeScope";
import ScrollBox, { RowCount } from "./ScrollBox";

// Credentials are per Ignition user and are what a project repository authenticates with. They
// could only be created from the Designer's setup wizard, which puts them behind the thing they
// are needed for: you cannot clone a project until the key exists, and you could not make the key
// without opening a Designer. This tab is the same records, reachable before that point.
type Kind = "SSH" | "HTTPS";
type Mode = "inline" | "reference";

const Credentials = () => {
  const { data: status } = useGetStatusQuery();
  // Polls only while a check the gateway queued (after an add) has not reported yet.
  const [pending, setPending] = React.useState(false);
  const { data, isFetching } = useGetCredentialsQuery(undefined, {
    pollingInterval: pending ? 3000 : 0,
  });
  const [check] = useCheckCredentialMutation();
  // Which row is being checked — one shared isLoading disabled every button and told you nothing
  // about which one was working.
  const [checkingId, setCheckingId] = React.useState<string | null>(null);
  const { data: providers } = useGetSecretProvidersQuery();
  const [add] = useAddCredentialMutation();
  const [remove] = useRemoveCredentialMutation();
  const toasts = useToastNotifications();

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
  // The credential being edited, and which of its definitions the drawer edits (4.2.0).
  const modes = useModes();
  const [editing, setEditing] = React.useState<CredentialOption | null>(null);
  const [collection, setCollection] = useEditing(open, editing?.from);
  const credRef = editing
    ? { type: "credential" as const, id: editing.id, credType: editing.type }
    : undefined;
  const { data: def } = useGetDefinitionQuery(
    { ...(credRef ?? { type: "policy" }), collection },
    { skip: !open || !credRef || !modes.length }
  );
  const credDefs = {
    record: credRef,
    defined: def?.defined ?? editing?.defined ?? [],
    editing: collection,
    onEditing: setCollection,
  };
  // Another definition chosen: its fields replace the form's (the secret stays blank).
  React.useEffect(() => {
    if (!open || !def || def.collection !== collection) return;
    if (typeof def.name === "string") setName(def.name);
    if (typeof def.host === "string") setHost(def.host);
    if (typeof def.username === "string") setUsername(def.username);
  }, [open, def, collection]);

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
    setEditing(null);
    setMode("inline");
    setOpen(true);
  };
  const openEdit = (c: CredentialOption) => {
    reset();
    setEditId(c.id);
    setEditing(c);
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
    const scope = { saveTo: saveToParam(modes, collection) };
    await add(
      editId !== null
        ? { ...body, id: editId, ...scope }
        : { ...body, ...scope }
    ).unwrap();
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
  // A credential only another mode defines: this gateway never uses or checks it.
  const activeMode = status?.policy?.mode ?? null;
  const elsewhere = (c: CredentialOption) =>
    !!c.defined?.length &&
    !c.defined.includes("core") &&
    !(activeMode && c.defined.includes(activeMode));
  React.useEffect(
    () =>
      setPending(
        (data?.credentials ?? []).some((c) => c.check === null && !elsewhere(c))
      ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [data, activeMode]
  );

  return (
    <div>
      <div className="gitcfg-page-head">
        <div>
          <h3>
            Credentials
            <RowCount n={rows.length} noun="credential" />
          </h3>
          <p>
            Credentials this gateway uses for repositories. Expiry and scope are
            checked on save, daily and on Check.
          </p>
        </div>
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
        defs={credDefs}
      >
        <>
          <Definitions {...credDefs} />
          {modes.length ? (
            <p className="gitcfg-hint">
              Encrypted with this gateway&apos;s key. For a per-mode secret,
              prefer a Secret Provider reference.
            </p>
          ) : null}
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
        <ScrollBox className="gitcfg-table-scroll gitcfg-cred-scroll">
          <table className="gitcfg-table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Credential</th>
                <th>Expires</th>
                <th>Scope</th>
                <th>Reaches</th>
                <th>Last checked</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={`${c.type}-${c.id}`}>
                  <td>{c.type}</td>
                  <td>
                    {c.label} <OverrideTag defined={c.defined} from={c.from} />
                    {c.check?.account &&
                    !c.label.endsWith(`— ${c.check.account}`) ? (
                      <span className="gitcfg-proj-title">
                        {c.check.account}
                      </span>
                    ) : null}
                  </td>
                  {elsewhere(c) ? (
                    <td colSpan={4} className="gitcfg-off">
                      Not used on this gateway: only{" "}
                      {(c.defined ?? []).join(", ")} has it.
                    </td>
                  ) : (
                    <>
                      <td>{expiry(c)}</td>
                      <td>{scope(c)}</td>
                      <td>{reach(c)}</td>
                      <td>{checked(c)}</td>
                    </>
                  )}
                  <td className="gitcfg-table-act">
                    <Button
                      colorClass="secondary"
                      disabled={checkingId !== null || elsewhere(c)}
                      onClick={() => {
                        setCheckingId(`${c.type}-${c.id}`);
                        check({ type: c.type, id: c.id })
                          .unwrap()
                          .then(() =>
                            toasts.notifySuccess(`Checked ${c.label}`)
                          )
                          .catch(errorToast(toasts, "Could not check it"))
                          .finally(() => setCheckingId(null));
                      }}
                    >
                      {checkingId === `${c.type}-${c.id}`
                        ? "Checking…"
                        : "Check"}
                    </Button>
                    <Button colorClass="secondary" onClick={() => openEdit(c)}>
                      Edit
                    </Button>
                    <Button
                      colorClass="destructive"
                      // Removed from its mode with Edit › ⋮ › Remove Override.
                      disabled={elsewhere(c)}
                      onClick={() => del(c.type, c.id, c.label)}
                    >
                      Remove
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollBox>
      )}
    </div>
  );
};

export default Credentials;

const DAY = 86400000;

/** DD/MM/YYYY from an ISO date. */
const ddmmyyyy = (iso: string) => iso.split("-").reverse().join("/");

const hhmm = (at: number) => new Date(at).toTimeString().slice(0, 5);

// A GitHub token's expiry. Amber within 14 days, matching the Projects tab's warning. A failure is
// shown in place of the date: "could not ask" and "nothing to report" rendered as the same dash,
// which made a working check look broken.
function expiry(c: CredentialOption) {
  const k = c.check;
  if (!k) return <span className="gitcfg-off">Checking…</span>;
  if (k.rejected)
    return <span className="gitcfg-err">Rejected — expired or revoked</span>;
  if (k.error)
    return (
      <span className="gitcfg-err" title={k.error}>
        Could not check — {k.error}
      </span>
    );
  if (!k.expires)
    return (
      <span
        className="gitcfg-off"
        title="Only a GitHub token reports an expiry"
      >
        —
      </span>
    );
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

// When the host was last asked. The time alone is only honest on the day it happened — a daily
// check that stopped running would otherwise read as this morning's.
function checked(c: CredentialOption) {
  const k = c.check;
  if (!k) return <span className="gitcfg-off">—</span>;
  const at = new Date(k.checkedAt);
  const today = new Date().toDateString() === at.toDateString();
  return today
    ? hhmm(k.checkedAt)
    : `${at.getDate().toString().padStart(2, "0")}/${(at.getMonth() + 1)
        .toString()
        .padStart(2, "0")} ${hhmm(k.checkedAt)}`;
}

// What the token is allowed to touch at all. A classic token reaches every repository its account
// can, which is worth flagging beside one scoped to a single repo.
function scope(c: CredentialOption) {
  const s = c.check?.scope;
  if (!s) return <span className="gitcfg-off">—</span>;
  const label = s.kind === "classic" ? "Classic" : "Fine-grained";
  return (
    <span
      className={s.kind === "classic" ? "gitcfg-dirty" : ""}
      title={s.repos.length ? s.repos.join("\n") : undefined}
    >
      {label} — {s.summary}
    </span>
  );
}

// Each remote using the credential, and what it may do there.
function reach(c: CredentialOption) {
  const k = c.check;
  if (!k) return null;
  if (k.reach.length === 0)
    return <span className="gitcfg-off">No project uses this yet</span>;
  return k.reach.map((r) => (
    <span
      key={r.target}
      className={`gitcfg-reach ${r.read ? "" : "gitcfg-err"}`}
      title={r.error ?? undefined}
    >
      {r.target}:{" "}
      {r.push
        ? "read, push"
        : r.read
        ? "read"
        : r.error
        ? r.error
        : "no access"}
    </span>
  ));
}

/** The token, with a copy button: retyping one is how it breaks. */
export function Snippet({ text }: { text: string }) {
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
