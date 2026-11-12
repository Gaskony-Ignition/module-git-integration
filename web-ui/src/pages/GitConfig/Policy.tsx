import React from "react";
import { Button, Loading, useToastNotifications } from "../../webui";
import { HelpButton, TextInput } from "./fields";
import SettingsDrawer from "./SettingsDrawer";
import AuditSetting from "./AuditSetting";
import ScrollBox from "./ScrollBox";
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

// What each setting allows, shown behind its "?" so the table stays short.
const HELP: Record<string, string> = {
  pushAllowed:
    "Sending commits to a remote, from a Designer or this page. Off: commits stay on this gateway.",
  forcePushAllowed:
    "When a remote rejects a Designer's push because it has commits this gateway lacks, offers to overwrite the remote branch. Off: pull first. Overwriting removes those newer commits from the remote.",
  designerCommit:
    "Committing, reverting and completing a merge from a Designer. Off: Designers can still see changes and history. The gateway's own config commits are not affected.",
  branchSwitch:
    "Creating, checking out and deleting branches, and checking out an older commit, from a Designer or this page. Off: each repository stays on its current branch.",
  layoutSwitch:
    "Switching between Separate (a repository per project plus one for config) and Combined (one repository for the whole gateway), under Configure versioning.",
  allowInsecureRemotes:
    "Remotes with no TLS or on this machine: file://, http://, git:// and localhost. Off: only https:// and SSH to another host.",
  githubMergedOnly:
    "A delivery installs a branch tip only when GitHub signed it, as it does for a pull request merged on GitHub, and only from a GitHub host below. A commit pushed straight to the branch is refused and logged.",
  deliveries:
    "How projects and the gateway repository may be updated without anyone at a Designer. Sync — pull merges the branch in when it moves and waits while anyone has uncommitted changes. Sync — replace makes the project match the branch exactly, overwriting local edits. Runner takes releases and updates from a GitHub Actions runner. Off is always allowed.",
  allowedRemoteHosts:
    "Hosts a remote may point at, checked before the gateway connects and when a remote is saved. *.example.com matches subdomains. Blank allows any host.",
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
  // The one setting whose explanation is showing, in the table or the drawer.
  const [help, setHelp] = React.useState<string | null>(null);
  const toggle = (key: string) => setHelp(help === key ? null : key);
  const helpButton = (key: string, label: string) => (
    <HelpButton
      label={label}
      open={help === key}
      onToggle={() => toggle(key)}
    />
  );
  const helpRow = (key: string) =>
    help === key ? (
      <tr className="gitcfg-help-row">
        <td colSpan={2}>{HELP[key]}</td>
      </tr>
    ) : null;
  const [draft, setDraft] = React.useState<PolicyFields | null>(null);
  const [hosts, setHosts] = React.useState("");
  const [ghHosts, setGhHosts] = React.useState("github.com");
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
      githubHosts: p.githubHosts ?? ["github.com"],
      deliveryModes: p.deliveryModes,
      allowedRemoteHosts: p.allowedRemoteHosts,
    });
    setHosts(p.allowedRemoteHosts.join(", "));
    setGhHosts((p.githubHosts ?? ["github.com"]).join(", "));
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
      githubHosts: ghHosts
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

      <ScrollBox className="gitcfg-table-scroll gitcfg-policy-scroll">
        <table className="gitcfg-table">
          <thead>
            <tr>
              <th>Setting</th>
              <th>Value</th>
            </tr>
          </thead>
          <tbody>
            {FLAGS.map(([key, label]) => (
              <React.Fragment key={key}>
                <tr>
                  <td>
                    {label}
                    {helpButton(key, label)}
                  </td>
                  <td>{onOff(key, !!policy[key])}</td>
                </tr>
                {helpRow(key)}
              </React.Fragment>
            ))}
            <tr>
              <td>
                Deliveries
                {helpButton("deliveries", "Deliveries")}
              </td>
              <td>
                {DELIVERIES.filter(([d]) => policy.deliveryModes.includes(d))
                  .map(([, l]) => l)
                  .join(", ") || "Off only"}
              </td>
            </tr>
            {helpRow("deliveries")}
            <tr>
              <td>
                Allowed remote hosts
                {helpButton("allowedRemoteHosts", "Allowed remote hosts")}
              </td>
              <td>
                {policy.allowedRemoteHosts.length
                  ? policy.allowedRemoteHosts.join(", ")
                  : "Any"}
              </td>
            </tr>
            {helpRow("allowedRemoteHosts")}
            <AuditSetting />
          </tbody>
        </table>
      </ScrollBox>

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
              <React.Fragment key={key}>
                <div className="gitcfg-check-row">
                  <label className="gitcfg-check">
                    <input
                      type="checkbox"
                      checked={draft[key] as boolean}
                      onChange={(e) =>
                        setDraft({ ...draft, [key]: e.target.checked })
                      }
                    />
                    <span>{label}</span>
                  </label>
                  {helpButton(key, label)}
                </div>
                {help === key ? (
                  <p className="gitcfg-hint">{HELP[key]}</p>
                ) : null}
              </React.Fragment>
            ))}
            <p className="gitcfg-hint">
              Deliveries allowed (Off always is allowed):
              {helpButton("deliveries", "Deliveries")}
            </p>
            {help === "deliveries" ? (
              <p className="gitcfg-hint">{HELP.deliveries}</p>
            ) : null}
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
            <TextInput
              label="GitHub hosts"
              value={ghHosts}
              placeholder="github.com"
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setGhHosts(e.target.value)
              }
            />
          </>
        ) : null}
      </SettingsDrawer>
    </div>
  );
};

export default Policy;
