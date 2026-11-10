import React from "react";
import { Button, Loading, useToastNotifications } from "../../webui";
import { TextInput } from "./fields";
import SettingsDrawer from "./SettingsDrawer";
import ModeScope, { FromTag, saveToParam, useSaveTo } from "./ModeScope";
import {
  DeliveryKind,
  PolicyFields,
  useGetStatusQuery,
  useSavePolicyMutation,
} from "./GitConfig.service";

const FLAGS: [keyof PolicyFields, string][] = [
  ["pushAllowed", "Push to remotes"],
  ["forcePushAllowed", "Force push (Designer, after a rejected push)"],
  ["designerCommit", "Commit from the Designer"],
  ["branchSwitch", "Create, switch and delete branches"],
  ["layoutSwitch", "Switch the repository layout"],
];

const DELIVERIES: [DeliveryKind, string][] = [
  ["pull", "Sync — pull"],
  ["replace", "Sync — replace"],
  ["runner", "Runner"],
];

const yesNo = (v: boolean) =>
  v ? (
    <span className="gitcfg-ok">Allowed</span>
  ) : (
    <span className="gitcfg-err">Off</span>
  );

// The Git policy: what this module may do on this gateway, and in which deployment mode. It is
// the one record of this module that is versioned with the config, so an override written for a
// mode travels to every gateway that follows the branch.
const Policy = () => {
  const { data, isLoading } = useGetStatusQuery();
  const [save] = useSavePolicyMutation();
  const toasts = useToastNotifications();
  const policy = data?.policy;
  const mode = policy?.mode ?? null;
  const [open, setOpen] = React.useState(false);
  const [draft, setDraft] = React.useState<PolicyFields | null>(null);
  const [hosts, setHosts] = React.useState("");
  const [saveTo, setSaveTo] = useSaveTo(open, policy?.from);

  if (isLoading || !policy) {
    return <Loading isLoading={true} />;
  }

  const openEdit = () => {
    setDraft({
      pushAllowed: policy.pushAllowed,
      forcePushAllowed: policy.forcePushAllowed,
      designerCommit: policy.designerCommit,
      branchSwitch: policy.branchSwitch,
      layoutSwitch: policy.layoutSwitch,
      deliveryModes: policy.deliveryModes,
      allowedRemoteHosts: policy.allowedRemoteHosts,
    });
    setHosts(policy.allowedRemoteHosts.join(", "));
    setOpen(true);
  };

  const submit = async () => {
    if (!draft) return;
    await save({
      ...draft,
      // Off is always allowed; listing it keeps the stored record readable.
      deliveryModes: [
        "off",
        ...draft.deliveryModes.filter((d) => d !== "off"),
      ] as DeliveryKind[],
      allowedRemoteHosts: hosts
        .split(/[\s,]+/)
        .map((h) => h.trim())
        .filter((h) => h !== ""),
      saveTo: saveToParam(mode, saveTo),
    }).unwrap();
    toasts.notifySuccess("Git policy saved");
  };

  const from = <FromTag from={policy.from || "core"} />;

  return (
    <div>
      <div className="gitcfg-page-head">
        <div>
          <h3>Policy</h3>
          <p>
            What Git Integration may do on this gateway
            {mode ? ` in deployment mode ${mode}` : ""}. The policy is versioned
            with the config, so a mode&apos;s override travels with the branch.
            Off is refused on the gateway, from the Designer and from the
            runner.
          </p>
        </div>
        <div className="gitcfg-actions">
          <Button colorClass="primary" onClick={openEdit}>
            Edit policy
          </Button>
        </div>
      </div>

      <div className="gitcfg-table-scroll">
        <table className="gitcfg-table">
          <thead>
            <tr>
              <th>Setting</th>
              <th>Value</th>
              <th>Comes from</th>
            </tr>
          </thead>
          <tbody>
            {FLAGS.map(([key, label]) => (
              <tr key={key}>
                <td>{label}</td>
                <td>{yesNo(policy[key] as boolean)}</td>
                <td>{from}</td>
              </tr>
            ))}
            <tr>
              <td>Deliveries</td>
              <td>
                {DELIVERIES.filter(([d]) => policy.deliveryModes.includes(d))
                  .map(([, l]) => l)
                  .join(", ") || "Off only"}
              </td>
              <td>{from}</td>
            </tr>
            <tr>
              <td>Allowed remote hosts</td>
              <td>
                {policy.allowedRemoteHosts.length
                  ? policy.allowedRemoteHosts.join(", ")
                  : "Any"}
              </td>
              <td>{from}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <SettingsDrawer
        open={open}
        title="Git policy"
        onClose={() => setOpen(false)}
        onSave={submit}
        errorTitle="Could not save the Git policy"
      >
        {draft ? (
          <>
            <ModeScope
              from={policy.from}
              saveTo={saveTo}
              onSaveTo={setSaveTo}
              reset={{ type: "policy" }}
            />
            {FLAGS.map(([key, label]) => (
              <label className="gitcfg-check" key={key}>
                <input
                  type="checkbox"
                  checked={draft[key] as boolean}
                  onChange={(e) =>
                    setDraft({ ...draft, [key]: e.target.checked })
                  }
                />
                <span>{label}</span>
              </label>
            ))}
            <p className="gitcfg-hint">Deliveries allowed (Off always is):</p>
            {DELIVERIES.map(([d, label]) => (
              <label className="gitcfg-check" key={d}>
                <input
                  type="checkbox"
                  checked={draft.deliveryModes.includes(d)}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      deliveryModes: e.target.checked
                        ? [...draft.deliveryModes, d]
                        : draft.deliveryModes.filter((x) => x !== d),
                    })
                  }
                />
                <span>{label}</span>
              </label>
            ))}
            <TextInput
              label="Allowed remote hosts"
              value={hosts}
              placeholder="github.com, *.example.com"
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setHosts(e.target.value)
              }
            />
            <p className="gitcfg-hint">
              Blank allows any host. Checked against a remote&apos;s URL before
              the gateway contacts it, and when a remote is saved.
            </p>
          </>
        ) : null}
      </SettingsDrawer>
    </div>
  );
};

export default Policy;
