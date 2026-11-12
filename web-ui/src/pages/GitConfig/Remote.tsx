import React from "react";
import { useFormContext } from "react-hook-form";
import {
  Button,
  Card,
  FormControlInput,
  Modal,
  Radio,
  TextArea,
  TextAutocomplete,
  TextInput,
  yup,
} from "../../webui";
import { FormContent, RESOURCE } from "../../gatewayLib";
import SingletonTab from "./SingletonTab";
import {
  RemoteResp,
  useGetDefinitionQuery,
  useGetSecretProvidersQuery,
  useGetSecretRefMutation,
  useGetStatusQuery,
  useRemoveRemoteMutation,
  useSaveRemoteMutation,
  useTestRemoteMutation,
} from "./GitConfig.service";

const DEFAULT_BRANCH = "main";
const n = "config.";

const errText = (e: any): string =>
  (e && e.data && (e.data.error || e.data.message)) ||
  (e && e.error) ||
  "Request failed";

const schema = yup.object({
  uri: yup.string().nullable(),
  branch: yup.string().nullable(),
});

// What the module's own routes take: the secret as typed, or the provider reference. A blank
// secret keeps the definition's saved one (the route asks again only for a new host).
const payload = (c: { [k: string]: unknown }) => ({
  uri: String(c.uri ?? "").trim(),
  mode: (c.mode === "reference" ? "reference" : "inline") as
    | "inline"
    | "reference",
  key: String(c.key ?? ""),
  password: String(c.password ?? ""),
  username: String(c.username ?? "").trim(),
  providerName: String(c.provider ?? ""),
  secretName: String(c.secretName ?? ""),
});

const RemoteForm = () => {
  const { watch, setValue, getValues, resetField } = useFormContext();
  const collection: string = watch("collection") || "core";
  const uri: string = watch(n + "uri") || "";
  const mode: string = watch(n + "mode") || "inline";
  const provider: string = watch(n + "provider") || "";
  const secretName: string = watch(n + "secretName") || "";
  const { data: status } = useGetStatusQuery();
  const combined = status?.layout === "combined";
  const { data: providersResp } = useGetSecretProvidersQuery();
  const { data: def } = useGetDefinitionQuery({ type: "remote", collection });
  const [getSecretRef] = useGetSecretRefMutation();
  const [testRemote, { isLoading: testing }] = useTestRemoteMutation();
  const [removeRemote] = useRemoveRemoteMutation();
  const [feedback, setFeedback] = React.useState<{
    ok: boolean;
    text: string;
  } | null>(null);
  const [confirmRemove, setConfirmRemove] = React.useState(false);

  const d = def as unknown as RemoteResp | undefined;
  const configured = !!d?.configured;
  // The secret's form comes from the module (the platform's record holds only a credential id):
  // set as the fields' saved values, so loading a definition does not count as an edit.
  React.useEffect(() => {
    if (!d || (def && def.collection !== collection)) return;
    const saved = (name: string, v: string) =>
      resetField(n + name, { defaultValue: v });
    saved("mode", d.secretMode || "inline");
    saved("username", d.username || "");
    saved("password", "");
    saved("key", "");
    saved("provider", "");
    saved("secretName", "");
    if (d.secretMode === "reference") {
      getSecretRef({ collection })
        .unwrap()
        .then((r) => {
          saved("provider", r.providerName || "");
          saved("secretName", r.secretName || "");
        })
        .catch(() => undefined);
    }
    setFeedback(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [def, collection]);

  const credType =
    uri && !uri.trim().toLowerCase().startsWith("http") ? "SSH" : "HTTPS";
  const providerNames = (providersResp?.providers ?? []).map((p) => p.name);
  const noProviders = providerNames.length === 0;
  const secretNames =
    providersResp?.providers.find((p) => p.name === provider)?.secrets ?? [];
  const keepHint = configured ? " (leave blank to keep current)" : "";

  const doTest = async () => {
    setFeedback(null);
    try {
      const res: any = await testRemote(
        payload(getValues("config") ?? {})
      ).unwrap();
      setFeedback({
        ok: true,
        text:
          res && res.fingerprint
            ? `Connection OK. Server key ${res.fingerprint}.`
            : "Connection OK.",
      });
    } catch (e: any) {
      setFeedback({ ok: false, text: errText(e) });
    }
  };

  return (
    <FormContent>
      <div className="cardContainer">
        <section id="git-remote">
          <Card title="REMOTE">
            <FormControlInput
              name={n + "uri"}
              id="cfg-uri"
              label="Repository URI"
              description={
                combined
                  ? "SSH or HTTPS URL of the gateway repository's remote: config and every project."
                  : "SSH or HTTPS URL of the config repository's remote."
              }
              input={
                <TextInput placeholder="ssh://git@host/org/gateway-config.git" />
              }
            />
            <FormControlInput
              name={n + "branch"}
              id="cfg-branch"
              label="Branch"
              description="Remote branch to push to."
              input={<TextInput placeholder={DEFAULT_BRANCH} />}
            />
          </Card>
        </section>
        <section id="git-remote-secret">
          <Card title="CREDENTIAL">
            <p className="gitcfg-hint">
              For a different secret per deployment mode, prefer a Secret
              Provider reference.
            </p>
            <FormControlInput
              name={n + "mode"}
              id="cfg-mode"
              label="Secret"
              input={
                <Radio
                  name="cfg-mode"
                  value={mode}
                  radios={[
                    { label: "Embedded", value: "inline" },
                    { label: "Referenced", value: "reference" },
                  ]}
                  onChange={(_e: any, value: string) =>
                    setValue(n + "mode", value, { shouldDirty: true })
                  }
                />
              }
            />
            {credType === "HTTPS" ? (
              <FormControlInput
                name={n + "username"}
                id="cfg-username"
                label="Username"
                input={<TextInput />}
              />
            ) : null}
            {mode === "inline" ? (
              <FormControlInput
                name={n + (credType === "SSH" ? "key" : "password")}
                id="cfg-secret"
                label={
                  (credType === "SSH" ? "Private key" : "Password / token") +
                  keepHint
                }
                input={
                  credType === "SSH" ? (
                    <TextArea placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" />
                  ) : (
                    <TextInput type="password" />
                  )
                }
              />
            ) : (
              <>
                <FormControlInput
                  name={n + "provider"}
                  id="cfg-provider"
                  label="Provider"
                  disabled={noProviders}
                  description={
                    noProviders
                      ? "No secret providers exist. Create one under Security."
                      : "Select a secret provider."
                  }
                  input={
                    <TextAutocomplete
                      id="cfg-provider-ac"
                      options={providerNames}
                      value={provider}
                      disabled={noProviders}
                      placeholder="Select secret provider"
                      onChange={(value: string) => {
                        setValue(n + "provider", value || "", {
                          shouldDirty: true,
                        });
                        setValue(n + "secretName", "", { shouldDirty: true });
                      }}
                    />
                  }
                />
                <FormControlInput
                  name={n + "secretName"}
                  id="cfg-secretname"
                  label="Secret name"
                  disabled={noProviders || !provider}
                  description="Select from the list or enter a secret name."
                  input={
                    <TextAutocomplete
                      id="cfg-secretname-ac"
                      freeSolo
                      options={secretNames}
                      value={secretName}
                      disabled={noProviders || !provider}
                      placeholder="Select or enter a secret name…"
                      onChange={(value: string) =>
                        setValue(n + "secretName", value || "", {
                          shouldDirty: true,
                        })
                      }
                    />
                  }
                />
              </>
            )}
            {feedback ? (
              <p className={feedback.ok ? "gitcfg-ok" : "gitcfg-err"}>
                {feedback.text}
              </p>
            ) : null}
            <div className="gitcfg-actions">
              <Button disabled={testing || !uri.trim()} onClick={doTest}>
                {testing ? "Testing…" : "Test connection"}
              </Button>
              {configured && collection === (d?.from || "core") ? (
                <Button
                  colorClass="destructive"
                  onClick={() => setConfirmRemove(true)}
                >
                  Remove remote…
                </Button>
              ) : null}
            </div>
          </Card>
        </section>
      </div>
      <Modal
        open={confirmRemove}
        type="confirm"
        title="Remove remote"
        modalConfig={{
          confirmationText:
            "Remove the remote this gateway uses? The local history is kept; only the push destination and its credential link are removed.",
          primaryText: "Remove",
          secondaryText: "Cancel",
          warningGeneral: true,
        }}
        onClose={() => setConfirmRemove(false)}
        onConfirm={() => {
          setConfirmRemove(false);
          removeRemote();
        }}
      />
    </FormContent>
  );
};

// The gateway repository's remote on the platform's settings page, overridable per mode.
const Remote = () => {
  const [saveRemote] = useSaveRemoteMutation();
  return (
    <SingletonTab
      type="remote"
      resourceType={RESOURCE.remote}
      title="Gateway Remote"
      schema={schema}
      transformData={(data: any) => ({
        ...data,
        // The credential id stays on the gateway; the form holds what the route takes.
        config: {
          uri: data?.config?.uri ?? "",
          branch: data?.config?.branch ?? "",
        },
      })}
      send={async (config, saveTo) => {
        const p = payload(config);
        if (!p.uri) throw { error: "Enter the repository URI." };
        await saveRemote({
          ...p,
          branch: String(config.branch ?? "").trim() || DEFAULT_BRANCH,
          saveTo,
        }).unwrap();
      }}
    >
      <RemoteForm />
    </SingletonTab>
  );
};

export default Remote;
