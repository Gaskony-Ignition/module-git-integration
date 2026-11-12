import React, { useEffect, useState } from "react";
import { DeleteGw } from "@inductiveautomation/ignition-icons";
import {
  Button,
  Checkbox,
  Drawer,
  DrawerTemplate,
  DrawerTemplateSize,
  DrawerTemplateColorTheme,
  Modal,
  useToastNotifications,
} from "../../webui";
import { HelpTip, SelectInput, TextInput as LabelledTextInput } from "./fields";
import { selectValue } from "./selectValue";
import { errorToast } from "./errors";
import { useOpenTab } from "./tabs";
import {
  useGetRemoteQuery,
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

// The "Configure versioning" lateral panel: the gateway repository's delivery, layout and removal.
// Its remote moved to the Remote tab (4.5.0), the platform's settings page, so each deployment
// mode can have its own through the gateway's Resource Definitions menu.
const ConfigDrawer = ({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) => {
  const { data: remote } = useGetRemoteQuery();
  const [deinit] = useDeinitMutation();
  const [saveDelivery, { isLoading: saving }] = useSaveConfigDeliveryMutation();
  const [syncNow, { isLoading: syncing }] = useConfigSyncNowMutation();
  const toasts = useToastNotifications();
  const openTab = useOpenTab();
  const [deliveryMode, setDeliveryMode] = useState<ConfigDeliveryMode>("off");
  const { data: status } = useGetStatusQuery();
  const [saveLayout, { isLoading: switching }] = useSaveLayoutMutation();
  const layout: Layout =
    status && status.layout === "combined" ? "combined" : "separate";
  const combined = layout === "combined";
  const [confirmLayout, setConfirmLayout] = useState(false);
  const [restoreHistory, setRestoreHistory] = useState(true);
  const [archiveLeftovers, setArchiveLeftovers] = useState(false);
  const [deliveryBranch, setDeliveryBranch] = useState("");
  const [deliveryEvery, setDeliveryEvery] = useState("300");
  const refused = status?.policy?.refused ?? {};
  const [confirmDeinit, setConfirmDeinit] = useState(false);
  const [dropRemote, setDropRemote] = useState(false);
  // Three tabs, so no part of the drawer scrolls inside it at 1366x640.
  const [tab, setTab] = useState<"delivery" | "layout" | "delete">("delivery");

  useEffect(() => {
    if (!open) {
      return;
    }
    setTab("delivery");
    if (!remote) {
      return;
    }
    setDeliveryMode(asMode(remote.delivery?.mode || "off"));
    setDeliveryBranch(
      remote.delivery?.branch || remote.branch || DEFAULT_BRANCH
    );
    setDeliveryEvery(String(remote.delivery?.intervalSeconds || 300));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const configured = !!(remote && remote.configured);

  const onSave = async () => {
    try {
      await saveDelivery({
        mode: deliveryMode,
        branch: deliveryBranch.trim(),
        intervalSeconds: Number(deliveryEvery) || 300,
      }).unwrap();
      onClose();
    } catch (e: any) {
      errorToast(toasts, "Could not save the delivery")(e);
    }
  };

  const deliveryHelp =
    deliveryMode === "replace"
      ? `Replace makes the gateway ${
          combined ? "" : "config "
        }match the branch exactly. Local commits and uncommitted changes are discarded, and a branch moved backwards is followed.`
      : deliveryMode === "pull"
      ? "Pull merges the branch in, and refuses while anything on the gateway is uncommitted."
      : deliveryMode === "runner"
      ? "A runner workflow calls the gateway to pull the branch; no timer."
      : "Off: nothing reaches this gateway from the remote by itself.";

  const drawerBody = (
    <div className="gitcfg gitcfg-form">
      <div className="gitcfg-tabs" role="tablist">
        {(
          [
            ["delivery", "Delivery"],
            ["layout", "Layout"],
            ["delete", "Delete"],
          ] as const
        ).map(([key, text]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={tab === key}
            className={tab === key ? "is-active" : ""}
            onClick={() => setTab(key)}
          >
            {text}
          </button>
        ))}
      </div>

      {tab === "delivery" ? (
        <>
          <p className="gitcfg-hint">
            {configured
              ? `Remote: ${remote?.uri} (${remote?.branch || DEFAULT_BRANCH}),`
              : "No remote yet. It is"}{" "}
            set with its credential on the Remote tab.
          </p>
          <div className="gitcfg-actions">
            <Button
              colorClass="secondary"
              onClick={() => {
                onClose();
                openTab("remote");
              }}
            >
              Open the Remote tab
            </Button>
          </div>
          {configured ? (
            <>
              <SelectInput
                label={
                  <>
                    How changes reach this gateway
                    <HelpTip label="How changes reach this gateway">
                      {deliveryHelp}
                      {combined
                        ? " Combined layout: this delivery moves config and every project."
                        : ""}
                    </HelpTip>
                  </>
                }
                inputProps={{ "aria-label": "How changes reach this gateway" }}
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
                    label: off
                      ? `${o.label} (not allowed by policy)`
                      : o.value === "replace"
                      ? `${o.label} (overwrites local edits)`
                      : o.label,
                    value: o.value,
                    disabled: (o.combinedOnly && !combined) || !!off,
                  };
                })}
                onChange={(e: unknown) =>
                  setDeliveryMode(asMode(selectValue(e)))
                }
              />
              {deliveryMode !== "off" ? (
                <div className="gitcfg-field-row">
                  <LabelledTextInput
                    label="Branch"
                    value={deliveryBranch}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                      setDeliveryBranch(e.target.value)
                    }
                  />
                  {deliveryMode !== "runner" ? (
                    <LabelledTextInput
                      label="Every (seconds, minimum 30)"
                      value={deliveryEvery}
                      onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                        setDeliveryEvery(e.target.value)
                      }
                    />
                  ) : null}
                </div>
              ) : null}
              {remote?.delivery?.last ? (
                <p
                  className={`gitcfg-last-run ${
                    remote.delivery.last.ok ? "gitcfg-ok" : "gitcfg-err"
                  }`}
                  title={remote.delivery.last.message}
                >
                  Last run{" "}
                  {new Date(remote.delivery.last.time).toLocaleString()}:{" "}
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
                        .then((r) =>
                          r.outcome === "ok"
                            ? toasts.notifySuccess(`Config: ${r.result}`)
                            : toasts.notifyError(`Config: ${r.result}`)
                        )
                        .catch(errorToast(toasts, "Sync failed"))
                    }
                  >
                    {syncing ? "Syncing…" : "Sync now"}
                  </Button>
                </div>
              ) : null}
            </>
          ) : null}
        </>
      ) : null}

      {tab === "layout" ? (
        <>
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
              <Checkbox
                defaultValue={archiveLeftovers}
                disabled={!!refused.LAYOUT_SWITCH}
                onChange={(_e: unknown, checked: boolean) =>
                  setArchiveLeftovers(checked)
                }
                label="Archive leftover .git folders in project folders (to var/git-archive)"
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
        </>
      ) : null}

      {tab === "delete" ? (
        <>
          <p className="gitcfg-hint">
            {combined
              ? "Switch the layout to Separate before deleting versioning: this repository holds every project too."
              : "This permanently deletes the gateway's versioning history."}
          </p>
          <label className="gitcfg-check">
            <input
              type="checkbox"
              checked={dropRemote}
              onChange={(e) => setDropRemote(e.target.checked)}
            />
            <span>
              Also remove the saved remote (kept otherwise, so initialising
              again reconnects)
            </span>
          </label>
          <div className="gitcfg-actions">
            <Button
              colorClass="destructive"
              startIcon={<DeleteGw width={16} height={16} />}
              onClick={() => setConfirmDeinit(true)}
            >
              Delete versioning
            </Button>
          </div>
        </>
      ) : null}
    </div>
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
          primaryDisabled={saving || !configured}
          onComplete={onSave}
          onCancel={onClose}
          onClose={onClose}
        >
          {drawerBody}
        </DrawerTemplate>
      </Drawer>

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
            archiveLeftovers,
          })
            .unwrap()
            .then((r) => {
              toasts.notifySuccess(
                r.layout === "combined"
                  ? `Combined ${r.projects} project(s) into the gateway repository`
                  : `Separated ${r.projects} project(s)` +
                      (r.restored ? `, ${r.restored} with earlier history` : "")
              );
              if (r.warning) toasts.notifyError(r.warning);
            })
            .catch(errorToast(toasts, "Could not switch the layout"));
        }}
      />

      <Modal
        open={confirmDeinit}
        type="confirm"
        title="Delete versioning"
        modalConfig={{
          confirmationText: dropRemote
            ? "Removing versioning deletes all version history in the gateway data directory, and the saved remote. Saved credentials are kept."
            : "Removing versioning deletes all version history in the gateway data directory. The saved remote and credentials are kept.",
          primaryText: "Delete versioning",
          secondaryText: "Cancel",
          warningGeneral: true,
        }}
        onClose={() => setConfirmDeinit(false)}
        onConfirm={() => {
          setConfirmDeinit(false);
          deinit({ removeRemote: dropRemote });
          onClose();
        }}
      />
    </>
  );
};

export default ConfigDrawer;
