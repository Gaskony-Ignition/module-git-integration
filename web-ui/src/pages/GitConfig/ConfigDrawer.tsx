import React, { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { DeleteGw } from "@inductiveautomation/ignition-icons";
import {
  Button,
  Card,
  Checkbox,
  Drawer,
  DrawerTemplate,
  DrawerTemplateSize,
  DrawerTemplateColorTheme,
  Form,
  FormControlInput,
  Modal,
  Radio,
  TextArea,
  TextAutocomplete,
  TextInput,
  useToastNotifications,
} from "../../webui";
import { SelectInput, TextInput as LabelledTextInput } from "./fields";
import { selectValue } from "./selectValue";
import { errorToast } from "./errors";
import Definitions, {
  DefinitionMenu,
  saveToParam,
  useEditing,
  useModes,
} from "./ModeScope";
import {
  RemoteResp,
  useGetDefinitionQuery,
  useGetRemoteQuery,
  useGetSecretProvidersQuery,
  useGetSecretRefMutation,
  useSaveRemoteMutation,
  useRemoveRemoteMutation,
  useTestRemoteMutation,
  useDeinitMutation,
  useSaveConfigDeliveryMutation,
  useConfigSyncNowMutation,
  useGetStatusQuery,
  useSaveLayoutMutation,
  ConfigDeliveryMode,
  Layout,
} from "./GitConfig.service";

const DEFAULT_BRANCH = "main";

const DELIVERY_OPTIONS: {
  label: string;
  value: ConfigDeliveryMode;
  combinedOnly: boolean;
}[] = [
  { label: "Off", value: "off", combinedOnly: false },
  { label: "Sync — pull", value: "pull", combinedOnly: true },
  { label: "Sync — replace", value: "replace", combinedOnly: false },
  { label: "Runner — repo updates", value: "runner", combinedOnly: true },
];

const asMode = (v: string): ConfigDeliveryMode =>
  v === "replace" || v === "pull" || v === "runner" ? v : "off";

const errText = (e: any): string =>
  (e && e.data && e.data.error) || "Request failed";

interface FormShape {
  uri: string;
  branch: string;
  mode: "inline" | "reference";
  key: string; // embedded SSH private key
  password: string; // embedded HTTPS password/token
  username: string; // HTTPS username
  provider: string; // referenced provider
  secretName: string; // referenced secret name
}

// The "Configure versioning" lateral panel. The remote's secret is entered/referenced inline in
// the drawer (no credential dropdown, no popup): choose Embedded or Referenced with a radio, then
// either type the secret or point at a gateway Secret Provider secret. The gateway owns a single
// dedicated credential record for the config remote (auto-named), so no name is asked for.
const ConfigDrawer = ({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) => {
  const { data: remote } = useGetRemoteQuery();
  const { data: providersResp } = useGetSecretProvidersQuery();
  const [saveRemote, { isLoading: saving }] = useSaveRemoteMutation();
  const [removeRemote] = useRemoveRemoteMutation();
  const [getSecretRef] = useGetSecretRefMutation();
  const [testRemote, { isLoading: testing }] = useTestRemoteMutation();
  const [deinit] = useDeinitMutation();
  const [saveDelivery] = useSaveConfigDeliveryMutation();
  const [syncNow, { isLoading: syncing }] = useConfigSyncNowMutation();
  const toasts = useToastNotifications();
  const [deliveryMode, setDeliveryMode] = useState<ConfigDeliveryMode>("off");
  const { data: status } = useGetStatusQuery();
  const [saveLayout, { isLoading: switching }] = useSaveLayoutMutation();
  const layout: Layout =
    status?.layout === "combined" ? "combined" : "separate";
  const combined = layout === "combined";
  const [confirmLayout, setConfirmLayout] = useState(false);
  const [restoreHistory, setRestoreHistory] = useState(true);
  const [deliveryBranch, setDeliveryBranch] = useState("");
  const [deliveryEvery, setDeliveryEvery] = useState("300");
  const modes = useModes();
  const [editing, setEditing] = useEditing(open, remote?.from);
  const { data: def } = useGetDefinitionQuery(
    { type: "remote", collection: editing },
    { skip: !open || !modes.length || !remote?.configured }
  );
  const refused = status?.policy?.refused ?? {};

  const form = useForm<FormShape>({
    defaultValues: {
      uri: "",
      branch: DEFAULT_BRANCH,
      mode: "inline",
      key: "",
      password: "",
      username: "",
      provider: "",
      secretName: "",
    },
  });
  const { watch, setValue, getValues, reset, handleSubmit } = form;
  const uri = watch("uri");
  const mode = watch("mode");
  const key = watch("key");
  const password = watch("password");
  const provider = watch("provider");
  const secretName = watch("secretName");

  const [feedback, setFeedback] = useState<{
    ok: boolean;
    text: string;
  } | null>(null);
  const [confirmRemoveRemote, setConfirmRemoveRemote] = useState(false);
  const [confirmDeinit, setConfirmDeinit] = useState(false);

  // Prefill from the saved remote each time the drawer opens (embedded secrets stay blank), and
  // from the selected definition when another one is chosen.
  useEffect(() => {
    if (!open || !def || def.collection !== editing) {
      return;
    }
    const d = def as unknown as RemoteResp;
    reset({
      ...getValues(),
      uri: d.uri || "",
      branch: d.branch || DEFAULT_BRANCH,
      mode: d.secretMode || "inline",
      key: "",
      password: "",
      username: d.username || "",
      provider: d.providerName || "",
      secretName: d.secretName || "",
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, def, editing]);

  useEffect(() => {
    if (!open || !remote) {
      return;
    }
    reset({
      uri: remote.uri || "",
      branch: remote.branch || DEFAULT_BRANCH,
      mode: remote.secretMode || "inline",
      key: "",
      password: "",
      username: remote.username || "",
      provider: remote.providerName || "",
      secretName: remote.secretName || "",
    });
    setFeedback(null);
    setDeliveryMode(asMode(remote.delivery?.mode || "off"));
    setDeliveryBranch(
      remote.delivery?.branch || remote.branch || DEFAULT_BRANCH
    );
    setDeliveryEvery(String(remote.delivery?.intervalSeconds || 300));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // The referenced provider and secret come from a WRITE-only call, after the prefill above.
  const shownMode =
    (def as unknown as RemoteResp | undefined)?.secretMode ??
    remote?.secretMode;
  useEffect(() => {
    if (!open || shownMode !== "reference") {
      return;
    }
    getSecretRef({ collection: editing })
      .unwrap()
      .then((r) => {
        setValue("provider", r.providerName || "");
        setValue("secretName", r.secretName || "");
      })
      .catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, editing, shownMode]);

  const configured = !!(remote && remote.configured);
  const sshUri = !!uri && !uri.trim().toLowerCase().startsWith("http");
  const credType: "SSH" | "HTTPS" = sshUri ? "SSH" : "HTTPS";

  const providers = providersResp ? providersResp.providers : [];
  const providerNames = providers.map((p) => p.name);
  const noProviders = providerNames.length === 0;
  const secretNames = providers.find((p) => p.name === provider)?.secrets || [];

  // A secret is available to save when: referencing (provider + name), typing a new embedded
  // secret, or editing an already-configured remote (blank embedded secret keeps the current one).
  const hasSecret =
    mode === "reference"
      ? !!provider && !!secretName
      : (credType === "SSH" ? !!key : !!password) || configured;
  const saveEnabled = !!uri.trim() && hasSecret;

  const secretPayload = (d: FormShape) => ({
    uri: d.uri.trim(),
    mode: d.mode,
    key: d.key,
    password: d.password,
    username: d.username.trim(),
    providerName: d.provider,
    secretName: d.secretName,
  });

  const onSave = handleSubmit(async (d) => {
    setFeedback(null);
    try {
      await saveRemote({
        ...secretPayload(d),
        branch: d.branch.trim() || DEFAULT_BRANCH,
        saveTo: saveToParam(modes, editing),
      }).unwrap();
      // The remote record has to exist before a delivery can be set against it.
      await saveDelivery({
        mode: deliveryMode,
        branch: deliveryBranch.trim(),
        intervalSeconds: Number(deliveryEvery) || 300,
      }).unwrap();
      onClose();
    } catch (e: any) {
      setFeedback({ ok: false, text: errText(e) });
    }
  });

  const doTest = async () => {
    setFeedback(null);
    try {
      const res: any = await testRemote(secretPayload(getValues())).unwrap();
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

  const defs = {
    record: configured ? { type: "remote" as const } : undefined,
    defined: def?.defined ?? remote?.defined ?? [],
    editing,
    onEditing: setEditing,
  };

  const keepHint = configured ? " (leave blank to keep current)" : "";

  const drawerBody = (
    <Form context={form} className="gitcfg gitcfg-form">
      <Card title="Remote">
        <Definitions {...defs} />
        {modes.length ? (
          <p className="gitcfg-hint">
            Saved to the selected definition. For a per-mode secret, prefer a
            Secret Provider reference.
          </p>
        ) : null}
        <FormControlInput
          name="uri"
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
          name="branch"
          id="cfg-branch"
          label="Branch"
          description="Remote branch to push to."
          input={<TextInput placeholder={DEFAULT_BRANCH} />}
        />
        <FormControlInput
          name="mode"
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
                setValue("mode", value as "inline" | "reference")
              }
            />
          }
        />
        {credType === "HTTPS" ? (
          <FormControlInput
            name="username"
            id="cfg-username"
            label="Username"
            input={<TextInput />}
          />
        ) : null}
        {mode === "inline" ? (
          <FormControlInput
            name={credType === "SSH" ? "key" : "password"}
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
              name="provider"
              id="cfg-provider"
              label="Provider"
              disabled={noProviders}
              otherProps={{ disabled: noProviders }}
              description={
                <span className="gitcfg-desc">
                  Select a secret provider from the available options
                  {noProviders ? (
                    <span className="gitcfg-provider-error">
                      No Providers Exist. Please Create one.
                    </span>
                  ) : null}
                </span>
              }
              input={
                <TextAutocomplete
                  id="cfg-provider-ac"
                  options={providerNames}
                  value={provider}
                  disabled={noProviders}
                  placeholder="Select secret provider"
                  onChange={(value: string) => {
                    setValue("provider", value || "");
                    setValue("secretName", "");
                  }}
                />
              }
            />
            <FormControlInput
              name="secretName"
              id="cfg-secretname"
              label="Secret name"
              disabled={noProviders || !provider}
              otherProps={{ disabled: noProviders || !provider }}
              description="Select from the list or manually enter a secret name"
              input={
                <TextAutocomplete
                  id="cfg-secretname-ac"
                  freeSolo
                  options={secretNames}
                  value={secretName}
                  disabled={noProviders || !provider}
                  placeholder="Select or enter a secret name…"
                  onChange={(value: string) =>
                    setValue("secretName", value || "")
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
          <Button disabled={testing || !saveEnabled} onClick={doTest}>
            {testing ? "Testing…" : "Test connection"}
          </Button>
          {configured ? (
            <Button
              colorClass="destructive"
              onClick={() => setConfirmRemoveRemote(true)}
            >
              Remove remote…
            </Button>
          ) : null}
        </div>
      </Card>

      {configured ? (
        <Card title="Delivery">
          <SelectInput
            label="How changes reach this gateway"
            value={deliveryMode}
            values={DELIVERY_OPTIONS.filter(
              (o) => combined || !o.combinedOnly || o.value === deliveryMode
            ).map((o) => {
              // Refused by the Git policy: listed, disabled, and saying why.
              const off =
                o.value !== "off" && o.value !== deliveryMode
                  ? refused[`delivery:${o.value}`]
                  : undefined;
              return {
                label: off ? `${o.label} (not allowed by policy)` : o.label,
                value: o.value,
                disabled: (o.combinedOnly && !combined) || !!off,
              };
            })}
            onChange={(e: unknown) => setDeliveryMode(asMode(selectValue(e)))}
          />
          {combined ? (
            <p className="gitcfg-hint">
              Combined layout: this delivery moves config and every project.
            </p>
          ) : null}
          {deliveryMode !== "off" ? (
            <>
              {deliveryMode === "replace" ? (
                <p className="gitcfg-hint">
                  Replace makes the gateway {combined ? "" : "config "}match the
                  branch exactly. Local commits and uncommitted changes are
                  discarded, and a branch moved backwards is followed.
                </p>
              ) : deliveryMode === "pull" ? (
                <p className="gitcfg-hint">
                  Pull merges the branch in, and refuses while anything on the
                  gateway is uncommitted.
                </p>
              ) : (
                <p className="gitcfg-hint">
                  A runner workflow calls the gateway to pull the branch; no
                  timer.
                </p>
              )}
              <LabelledTextInput
                label="Branch"
                value={deliveryBranch}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setDeliveryBranch(e.target.value)
                }
              />
              {deliveryMode !== "runner" ? (
                <LabelledTextInput
                  label="Check every (seconds, minimum 30)"
                  value={deliveryEvery}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    setDeliveryEvery(e.target.value)
                  }
                />
              ) : null}
            </>
          ) : null}
          {remote?.delivery?.last ? (
            <p className={remote.delivery.last.ok ? "gitcfg-ok" : "gitcfg-err"}>
              Last run {new Date(remote.delivery.last.time).toLocaleString()}:{" "}
              {remote.delivery.last.message}
            </p>
          ) : null}
          {remote?.delivery?.mode && remote.delivery.mode !== "off" ? (
            <div className="gitcfg-actions">
              <Button
                colorClass="secondary"
                disabled={syncing}
                onClick={() =>
                  syncNow()
                    .unwrap()
                    .then((r) => toasts.notifySuccess(`Config: ${r.result}`))
                    .catch(errorToast(toasts, "Sync failed"))
                }
              >
                {syncing ? "Syncing…" : "Sync now"}
              </Button>
            </div>
          ) : null}
        </Card>
      ) : null}

      <Card title="Repository layout">
        <p className="gitcfg-hint">
          {combined
            ? "Combined: one repository holds config/ and projects/. Branches, the remote and pulls are gateway-wide — checking out a branch switches config and every project."
            : "Separate: each project is its own repository with its own remote, branches and delivery; this repository holds config only."}
        </p>
        {combined ? (
          <div className="gitcfg-layout-opt">
            <Checkbox
              defaultValue={restoreHistory}
              disabled={!!refused.LAYOUT_SWITCH}
              onChange={(_e: unknown, checked: boolean) =>
                setRestoreHistory(checked)
              }
              label="Restore each project's earlier history, where one was kept"
            />
          </div>
        ) : null}
        {refused.LAYOUT_SWITCH ? (
          <p className="gitcfg-hint">{refused.LAYOUT_SWITCH}</p>
        ) : null}
        <div className="gitcfg-actions">
          <Button
            colorClass="secondary"
            disabled={switching || !!refused.LAYOUT_SWITCH}
            onClick={() => setConfirmLayout(true)}
          >
            {switching
              ? "Switching…"
              : combined
              ? "Switch to Separate…"
              : "Switch to Combined…"}
          </Button>
        </div>
      </Card>

      <Card title="Danger Zone" className="gitcfg-danger-card">
        <p className="gitcfg-off">
          {combined
            ? "Switch the layout to Separate before deleting versioning: this repository holds every project too."
            : "This action will permanently delete the gateway's versioning"}
        </p>
        <Button
          colorClass="destructive"
          startIcon={<DeleteGw width={16} height={16} />}
          onClick={() => setConfirmDeinit(true)}
        >
          Delete versioning
        </Button>
      </Card>
    </Form>
  );

  return (
    <>
      <Drawer
        anchor="right"
        open={open}
        onClose={onClose}
        size={DrawerTemplateSize.SMALL}
      >
        <DrawerTemplate
          title="Configure versioning"
          path={["Configure versioning"]}
          theme={DrawerTemplateColorTheme.GREY}
          primaryActionText={saving ? "Saving…" : "Save"}
          secondaryActionText="Cancel"
          primaryDisabled={saving || !saveEnabled}
          onComplete={onSave}
          onCancel={onClose}
          onClose={onClose}
          headerEndAdornment={<DefinitionMenu {...defs} />}
        >
          {drawerBody}
        </DrawerTemplate>
      </Drawer>

      <Modal
        open={confirmRemoveRemote}
        type="confirm"
        title="Remove remote"
        modalConfig={{
          confirmationText:
            "Remove the configured remote? The local history is kept; only the push destination and its credential link are removed.",
          primaryText: "Remove",
          secondaryText: "Cancel",
          warningGeneral: true,
        }}
        onClose={() => setConfirmRemoveRemote(false)}
        onConfirm={() => {
          setConfirmRemoveRemote(false);
          removeRemote();
        }}
      />

      <Modal
        open={confirmLayout}
        type="confirm"
        title={combined ? "Switch to Separate" : "Switch to Combined"}
        modalConfig={{
          confirmationText: combined
            ? "Each project gets its own repository again: its earlier history if you ticked that and one was kept, otherwise a new repository with one commit of its current files. Project remotes and deliveries come back as they were; the gateway's pull and runner delivery stop. Refused if anything is uncommitted."
            : "Every project joins this repository in one commit. Each project's own .git is kept under var/git-archive, so its history and remotes can come back. Branches, the remote and pulls become gateway-wide, and project deliveries other than a runner release stop. Refused if anything is uncommitted.",
          primaryText: "Switch",
          secondaryText: "Cancel",
          warningGeneral: true,
        }}
        onClose={() => setConfirmLayout(false)}
        onConfirm={() => {
          setConfirmLayout(false);
          saveLayout({
            layout: combined ? "separate" : "combined",
            restoreHistory,
          })
            .unwrap()
            .then((r) =>
              toasts.notifySuccess(
                r.layout === "combined"
                  ? `Combined ${r.projects} project(s) into the gateway repository`
                  : `Separated ${r.projects} project(s)` +
                      (r.restored ? `, ${r.restored} with earlier history` : "")
              )
            )
            .catch(errorToast(toasts, "Could not switch the layout"));
        }}
      />

      <Modal
        open={confirmDeinit}
        type="confirm"
        title="Delete versioning"
        modalConfig={{
          confirmationText:
            "Removing versioning deletes all version history in the gateway data directory. Saved credentials are kept.",
          primaryText: "Delete versioning",
          secondaryText: "Cancel",
          warningGeneral: true,
        }}
        onClose={() => setConfirmDeinit(false)}
        onConfirm={() => {
          setConfirmDeinit(false);
          deinit();
          onClose();
        }}
      />
    </>
  );
};

export default ConfigDrawer;
