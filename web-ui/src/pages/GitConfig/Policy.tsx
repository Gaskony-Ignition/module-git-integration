import React from "react";
import { Button, Loading, useToastNotifications } from "../../webui";
import { TextInput } from "./fields";
import SettingsDrawer from "./SettingsDrawer";
import AuditSetting from "./AuditSetting";
import Definitions, {
  OverrideTag,
  saveToParam,
  useEditing,
  useModes,
} from "./ModeScope";
import {
  DeliveryKind,
  PolicyFields,
  useGetDefinitionQuery,
  useGetStatusQuery,
  useSavePolicyMutation,
} from "./GitConfig.service";

const FLAGS: [keyof PolicyFields, string][] = [
  ["pushAllowed", "Push to remotes"],
  ["forcePushAllowed", "Force push (Designer, after a rejected push)"],
  ["designerCommit", "Commit from the Designer"],
  ["branchSwitch", "Create, switch and delete branches"],
  ["layoutSwitch", "Switch the repository layout"],
  [
    "allowInsecureRemotes",
    "Insecure remotes (file://, http://, git://, this machine)",
  ],
  ["githubMergedOnly", "Only deliver commits merged on GitHub"],
];

// The value that allows more than the default is the one worth a second look; the rest are plain.
const RISKY: Partial<Record<keyof PolicyFields, boolean>> = {
  allowInsecureRemotes: true,
};

const DELIVERIES: [DeliveryKind, string][] = [
  ["pull", "Sync — pull"],
  ["replace", "Sync — replace"],
  ["runner", "Runner"],
];

const onOff = (key: keyof PolicyFields, v: boolean) => (
  <span className={RISKY[key] === v ? "gitcfg-err" : ""}>
    {v ? "On" : "Off"}
  </span>
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
  const modes = useModes();
  const [editing, setEditing] = useEditing(open, policy?.from);
  // The definition being edited, which on a gateway with modes need not be the one in use.
  const { data: def } = useGetDefinitionQuery(
    { type: "policy", collection: editing },
    { skip: !open || !modes.length }
  );

  const load = (p: PolicyFields) => {
    setDraft({
      pushAllowed: p.pushAllowed,
      forcePushAllowed: p.forcePushAllowed,
      designerCommit: p.designerCommit,
      branchSwitch: p.branchSwitch,
      layoutSwitch: p.layoutSwitch,
      allowInsecureRemotes: !!p.allowInsecureRemotes,
      githubMergedOnly: !!p.githubMergedOnly,
      deliveryModes: p.deliveryModes,
      allowedRemoteHosts: p.allowedRemoteHosts,
    });
    setHosts(p.allowedRemoteHosts.join(", "));
  };

  React.useEffect(() => {
    if (open && def && def.collection === editing) {
      load(def as unknown as PolicyFields);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, def, editing]);

  if (isLoading || !policy) {
    return <Loading isLoading={true} />;
  }

  const openEdit = () => {
    load(policy);
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
      saveTo: saveToParam(modes, editing),
    }).unwrap();
    toasts.notifySuccess("Git policy saved");
  };

  const defs = {
    record: { type: "policy" as const },
    defined: def?.defined ?? policy.defined ?? [],
    editing,
    onEditing: setEditing,
  };

  return (
    <div>
      <div className="gitcfg-page-head">
        <div>
          <h3>Policy</h3>
          <p>
            What Git Integration may do here
            {mode ? ` in ${mode}` : ""}. Versioned with the config. Settings
            from <strong>{policy.from || "core"}</strong>.{" "}
            <OverrideTag defined={policy.defined} from={policy.from} />
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
            </tr>
          </thead>
          <tbody>
            {FLAGS.map(([key, label]) => (
              <tr key={key}>
                <td>{label}</td>
                <td>{onOff(key, !!policy[key])}</td>
              </tr>
            ))}
            <tr>
              <td>Deliveries</td>
              <td>
                {DELIVERIES.filter(([d]) => policy.deliveryModes.includes(d))
                  .map(([, l]) => l)
                  .join(", ") || "Off only"}
              </td>
            </tr>
            <tr>
              <td>Allowed remote hosts</td>
              <td>
                {policy.allowedRemoteHosts.length
                  ? policy.allowedRemoteHosts.join(", ")
                  : "Any"}
              </td>
            </tr>
            <AuditSetting />
          </tbody>
        </table>
      </div>

      <SettingsDrawer
        open={open}
        title="Git policy"
        onClose={() => setOpen(false)}
        onSave={submit}
        errorTitle="Could not save the Git policy"
        defs={defs}
      >
        {draft ? (
          <>
            <Definitions {...defs} />
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
            <p className="gitcfg-hint">
              Deliveries allowed (Off always is allowed):
            </p>
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
            <p className="gitcfg-hint">
              Only deliver commits merged on GitHub: a delivery installs a
              branch tip only when GitHub signed it (a pull request merged on
              GitHub). Others are refused and logged.
            </p>
          </>
        ) : null}
      </SettingsDrawer>
    </div>
  );
};

export default Policy;
