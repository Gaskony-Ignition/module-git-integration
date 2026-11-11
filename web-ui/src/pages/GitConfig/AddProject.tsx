import React from "react";
import { Button, useToastNotifications } from "../../webui";
import { SelectInput, TextInput } from "./fields";
import SettingsDrawer from "./SettingsDrawer";
import { selectValue } from "./selectValue";
import { errorToast } from "./errors";
import {
  Delivery,
  useAddProjectMutation,
  useGetCredentialsQuery,
  usePeekProjectMutation,
} from "./GitConfig.service";

const DELIVERIES: [Delivery, string][] = [
  ["off", "Off"],
  ["sync-pull", "Sync — pull"],
  ["sync-replace", "Sync — replace"],
  ["runner-repo", "Runner — repo updates"],
  ["runner-release", "Runner — release"],
];

// Brings in a project this gateway does not have yet, cloned from its repository. Separate
// layout only: in Combined, projects arrive with the gateway repository.
const AddProject = ({ combined }: { combined: boolean }) => {
  const { data: creds } = useGetCredentialsQuery();
  const [peek, { isLoading: peeking }] = usePeekProjectMutation();
  const [add] = useAddProjectMutation();
  const toasts = useToastNotifications();
  const [open, setOpen] = React.useState(false);
  const [url, setUrl] = React.useState("");
  const [credId, setCredId] = React.useState("");
  const [branches, setBranches] = React.useState<string[]>([]);
  const [branch, setBranch] = React.useState("");
  const [name, setName] = React.useState("");
  const [delivery, setDelivery] = React.useState<Delivery>("off");

  const credentials = creds?.credentials ?? [];
  const chosen = credentials.find((c) => String(c.id) === credId);
  const auth = {
    sshKeyId: chosen?.type === "SSH" ? chosen.id : undefined,
    httpsCredentialId: chosen?.type === "HTTPS" ? chosen.id : undefined,
  };

  const openDrawer = () => {
    setUrl("");
    setCredId("");
    setBranches([]);
    setBranch("");
    setName("");
    setDelivery("off");
    setOpen(true);
  };

  // Reads the branches and the project's title, so the name and branch start filled in.
  const lookUp = () =>
    peek({ url: url.trim(), ...auth })
      .unwrap()
      .then((r) => {
        setBranches(r.branches);
        setBranch(r.defaultBranch);
        setName(r.suggestedName);
      })
      .catch(errorToast(toasts, "Could not read the repository"));

  const save = async () => {
    const r = await add({
      name: name.trim(),
      url: url.trim(),
      branch: branch || undefined,
      ...auth,
      delivery,
      intervalSeconds: 300,
    }).unwrap();
    toasts.notifySuccess(`Added ${r.project} from ${r.branch}`);
  };

  return (
    <>
      <Button
        colorClass="primary"
        disabled={combined}
        title={
          combined
            ? "Combined layout: projects come in with the gateway repository (pull)."
            : undefined
        }
        onClick={openDrawer}
      >
        Add from repository
      </Button>
      <SettingsDrawer
        open={open}
        title="Add project from repository"
        onClose={() => setOpen(false)}
        onSave={save}
        saveDisabled={!name.trim() || !url.trim() || !branches.length}
        errorTitle="Could not add the project"
      >
        <TextInput
          label="Repository URL"
          placeholder="https://github.com/org/repo.git"
          value={url}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => {
            setUrl(e.target.value);
            setBranches([]);
          }}
        />
        <SelectInput
          label="Credential"
          value={credId}
          values={credentials.map((c) => ({
            label: `${c.type} — ${c.label}`,
            value: String(c.id),
          }))}
          onChange={(e: unknown) => {
            setCredId(selectValue(e));
            setBranches([]);
          }}
        />
        <div className="gitcfg-actions">
          <Button
            colorClass="secondary"
            disabled={!url.trim() || !chosen || peeking}
            onClick={lookUp}
          >
            {peeking ? "Reading…" : "Read repository"}
          </Button>
        </div>
        {branches.length ? (
          <>
            <SelectInput
              label="Branch"
              value={branch}
              values={branches.map((b) => ({ label: b, value: b }))}
              onChange={(e: unknown) => setBranch(selectValue(e))}
            />
            <TextInput
              label="Project name"
              value={name}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setName(e.target.value)
              }
            />
            <p className="gitcfg-hint">
              Letters, digits, _ and - only. Taken from the project&apos;s
              title; it must not exist on this gateway yet.
            </p>
            <SelectInput
              label="Delivery"
              value={delivery}
              values={DELIVERIES.map(([v, l]) => ({ label: l, value: v }))}
              onChange={(e: unknown) =>
                setDelivery((selectValue(e) || "off") as Delivery)
              }
            />
          </>
        ) : (
          <p className="gitcfg-hint">
            Read the repository to choose its branch and the project&apos;s
            name. The branch needs a project.json at its root.
          </p>
        )}
      </SettingsDrawer>
    </>
  );
};

export default AddProject;
