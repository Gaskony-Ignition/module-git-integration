import React from "react";
import { Button, useToastNotifications } from "../../webui";
import { SelectInput } from "./fields";
import SettingsDrawer from "./SettingsDrawer";
import { selectValue } from "./selectValue";
import { useGetAuditQuery, useSaveAuditMutation } from "./GitConfig.service";

const GATEWAY = "__gateway";
const OFF = "__off";

// Which audit profile gets one row per essential action: push, delivery installed, branch and
// layout switch, restore, policy change, credential and mode override added or removed. Polls,
// auto-commits and refusals stay on the Logs tab.
const AuditSetting = () => {
  const { data } = useGetAuditQuery();
  const [save] = useSaveAuditMutation();
  const toasts = useToastNotifications();
  const [open, setOpen] = React.useState(false);
  const [choice, setChoice] = React.useState(GATEWAY);

  if (!data) return null;
  const current = !data.enabled ? OFF : data.profile || GATEWAY;
  const label = (v: string) =>
    v === OFF
      ? "Off"
      : v === GATEWAY
      ? data.gatewayHasProfile
        ? "The gateway's audit profile"
        : "The gateway's audit profile (none set, so off)"
      : v;

  // A row of the Policy table, though not part of the policy: it is this gateway's own setting.
  return (
    <tr>
      <td>
        Audit log
        <span className="gitcfg-proj-title">
          One row per push, delivery, branch or layout switch, restore, policy,
          credential and override change. This gateway only, not versioned.
        </span>
      </td>
      <td>
        {label(current)}{" "}
        <Button
          colorClass="link"
          onClick={() => {
            setChoice(current);
            setOpen(true);
          }}
        >
          Edit
        </Button>
        <SettingsDrawer
          open={open}
          title="Audit log"
          onClose={() => setOpen(false)}
          onSave={async () => {
            await save({
              enabled: choice !== OFF,
              profile: choice === OFF || choice === GATEWAY ? "" : choice,
            }).unwrap();
            toasts.notifySuccess("Audit setting saved");
          }}
        >
          <SelectInput
            label="Audit profile"
            value={choice}
            values={[GATEWAY, OFF, ...data.profiles].map((v) => ({
              label: label(v),
              value: v,
            }))}
            onChange={(e: unknown) => setChoice(selectValue(e) || GATEWAY)}
          />
          <p className="gitcfg-hint">
            The gateway&apos;s audit profile is its Gateway Audit Profile
            (Security › General Settings). Auditing never holds up a git action;
            a profile that cannot be written to is logged once in the gateway
            log.
          </p>
        </SettingsDrawer>
      </td>
    </tr>
  );
};

export default AuditSetting;
