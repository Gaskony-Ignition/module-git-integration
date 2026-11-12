import React from "react";
import { Card, Checkbox, FormControlInput, TextInput, yup } from "../../webui";
import { HelpButton } from "./fields";
import AuditSetting from "./AuditSetting";
import {
  FormContent,
  RESOURCE,
  ResourceModeSingletonPage,
  useConfigurationModeContext,
} from "../../gatewayLib";
import { OnGatewayStore, OnOwnStore } from "../../store/bridge";
import { useFormContext } from "react-hook-form";
import { DeliveryKind, PolicyFields } from "./GitConfig.service";
import { useNoBackupMenu, useSingletonSave } from "./singleton";
import { useSavePolicyMutation } from "./GitConfig.service";

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

// What each setting allows, shown behind its "?" so the form stays short.
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
  githubHosts:
    "The hosts whose GitHub signature counts for the option above: github.com, or your GitHub Enterprise host.",
};

const DELIVERIES: [DeliveryKind, string][] = [
  ["pull", "Sync — pull"],
  ["replace", "Sync — replace"],
  ["runner", "Runner"],
];

// A saved policy leaves out what is at its default (allowed), never reads it as off.
const DEFAULTS: PolicyFields = {
  pushAllowed: true,
  forcePushAllowed: true,
  designerCommit: true,
  branchSwitch: true,
  layoutSwitch: true,
  allowInsecureRemotes: false,
  githubMergedOnly: false,
  githubHosts: ["github.com"],
  deliveryModes: ["off", "pull", "replace", "runner"],
  allowedRemoteHosts: [],
};

const hostList = (v: unknown) =>
  String(v ?? "")
    .split(/[\s,]+/)
    .map((h) => h.trim())
    .filter((h) => h !== "");

// The stored record as the form edits it: lists of hosts as text, each delivery a tick.
const toForm = (c: Partial<PolicyFields> | undefined) => {
  const p = { ...DEFAULTS };
  Object.entries(c ?? {}).forEach(([k, v]) => {
    if (v !== null && v !== undefined) (p as any)[k] = v;
  });
  const form: Record<string, unknown> = { ...p };
  form.allowedRemoteHosts = p.allowedRemoteHosts.join(", ");
  form.githubHosts = p.githubHosts.join(", ");
  DELIVERIES.forEach(([d]) => {
    form[`delivery_${d}`] = p.deliveryModes.includes(d);
  });
  delete form.deliveryModes;
  return form;
};

const fromForm = (f: Record<string, unknown>): PolicyFields => ({
  pushAllowed: !!f.pushAllowed,
  forcePushAllowed: !!f.forcePushAllowed,
  designerCommit: !!f.designerCommit,
  branchSwitch: !!f.branchSwitch,
  layoutSwitch: !!f.layoutSwitch,
  allowInsecureRemotes: !!f.allowInsecureRemotes,
  githubMergedOnly: !!f.githubMergedOnly,
  // Off is always allowed; listing it keeps the stored record readable.
  deliveryModes: [
    "off",
    ...DELIVERIES.filter(([d]) => f[`delivery_${d}`]).map(([d]) => d),
  ],
  allowedRemoteHosts: hostList(f.allowedRemoteHosts),
  githubHosts: hostList(f.githubHosts),
});

const schema = yup.object({
  allowedRemoteHosts: yup.string().nullable(),
  githubHosts: yup.string().nullable(),
});

const CATEGORIES = [
  { label: "Actions", id: "git-policy-actions" },
  { label: "Deliveries", id: "git-policy-deliveries" },
  { label: "Remotes", id: "git-policy-remotes" },
  { label: "Audit", id: "git-policy-audit" },
];

const PolicyForm = () => {
  const { getValues } = useFormContext();
  const { mode } = useConfigurationModeContext();
  const n = `${mode === "primary" ? "config" : "backupConfig"}.`;
  // The one setting whose explanation is showing.
  const [help, setHelp] = React.useState<string | null>(null);
  const label = (key: string, text: string) => (
    <span className="gitcfg-label-help">
      {text}
      <HelpButton
        label={text}
        open={help === key}
        onToggle={() => setHelp(help === key ? null : key)}
      />
    </span>
  );
  const check = (key: string, text: string, field = key) => (
    <FormControlInput
      key={field}
      name={n + field}
      id={`git-policy-${field}`}
      label={label(key, text)}
      description={help === key ? HELP[key] : undefined}
      input={<Checkbox defaultValue={getValues(n + field)} label="Allowed" />}
    />
  );
  const text = (key: string, t: string, placeholder: string) => (
    <FormControlInput
      name={n + key}
      id={`git-policy-${key}`}
      label={label(key, t)}
      description={help === key ? HELP[key] : undefined}
      input={<TextInput placeholder={placeholder} />}
    />
  );
  return (
    <FormContent>
      <div className="cardContainer gitcfg-policy-form">
        <section id="git-policy-actions">
          <Card title="ACTIONS">
            <p className="gitcfg-hint">
              What Git Integration may do on a gateway running this definition.
              Versioned with the config, so a mode&apos;s definition travels to
              every gateway that follows the branch.
            </p>
            {FLAGS.slice(0, 5).map(([k, t]) => check(k, t))}
          </Card>
        </section>
        <section id="git-policy-deliveries">
          <Card title="DELIVERIES">
            <p className="gitcfg-hint">
              {label("deliveries", "Deliveries allowed (Off always is)")}
            </p>
            {help === "deliveries" ? (
              <p className="gitcfg-hint">{HELP.deliveries}</p>
            ) : null}
            {DELIVERIES.map(([d, t]) =>
              check("deliveries", t, `delivery_${d}`)
            )}
            {check("githubMergedOnly", FLAGS[6][1])}
            {text("githubHosts", "GitHub hosts", "github.com")}
          </Card>
        </section>
        <section id="git-policy-remotes">
          <Card title="REMOTES">
            {text(
              "allowedRemoteHosts",
              "Allowed remote hosts",
              "github.com, *.example.com"
            )}
            {check("allowInsecureRemotes", FLAGS[5][1])}
          </Card>
        </section>
        <section id="git-policy-audit">
          <Card title="AUDIT">
            <OnOwnStore>
              <table className="gitcfg-table">
                <tbody>
                  <AuditSetting />
                </tbody>
              </table>
            </OnOwnStore>
          </Card>
        </section>
      </div>
    </FormContent>
  );
};

// The Git policy on the platform's settings page: Save Changes and the Resource Definitions menu
// (Create Override, Move Definition, Remove Override) as on the gateway's own settings.
const Policy = () => {
  useNoBackupMenu();
  const [savePolicy] = useSavePolicyMutation();
  const save = useSingletonSave("policy", (config, saveTo) =>
    savePolicy({ ...fromForm(config), saveTo }).unwrap()
  );
  return (
    <div className="gitcfg-singleton">
      <OnGatewayStore>
        <ResourceModeSingletonPage
          resourceType={RESOURCE.policy}
          title="Git Policy"
          configSchema={schema}
          categories={CATEGORIES}
          transformData={(data: any) => ({
            ...data,
            config: toForm(data?.config),
          })}
          handleSubmit={save.handleSubmit}
          {...save.callbacks}
        >
          <PolicyForm />
        </ResourceModeSingletonPage>
      </OnGatewayStore>
    </div>
  );
};

export default Policy;
