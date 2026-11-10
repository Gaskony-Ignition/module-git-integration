import React from "react";
import { Modal, MenuButton, useToastNotifications } from "../../webui";
import { SelectInput } from "./fields";
import { selectValue } from "./selectValue";
import { errorToast } from "./errors";
import {
  RecordRef,
  useGetStatusQuery,
  useModeOverrideMutation,
  useModeResetMutation,
} from "./GitConfig.service";

/** The active deployment mode, or null when the gateway runs on core alone. */
export const useActiveMode = (): string | null => {
  const { data } = useGetStatusQuery();
  return data?.policy?.mode ?? null;
};

/** Every deployment mode created on this gateway, active or not. */
export const useModes = (): string[] => {
  const { data } = useGetStatusQuery();
  return data?.modes ?? [];
};

/** "from core" / "from Prod": which collection a record's value comes from. */
export const FromTag = ({ from }: { from?: string | null }) =>
  from ? (
    <span className={`gitcfg-from${from === "core" ? "" : " is-mode"}`}>
      from {from}
    </span>
  ) : null;

/**
 * The modes that define a record, for a list row: "Prod, Dev" with the effective one marked.
 * Nothing when only core defines it.
 */
export const OverrideTag = ({
  defined,
}: {
  defined?: string[];
  from?: string | null;
}) => {
  const active = useActiveMode();
  const modes = (defined ?? []).filter((d) => d !== "core");
  if (!modes.length) return null;
  return (
    <span className="gitcfg-from">
      {(defined ?? []).includes("core") ? "Overridden in" : "Only in"}{" "}
      {modes.map((m, i) => (
        <React.Fragment key={m}>
          {i ? ", " : ""}
          <span className={m === active ? "gitcfg-from is-mode" : undefined}>
            {m}
            {m === active ? " (active)" : ""}
          </span>
        </React.Fragment>
      ))}
    </span>
  );
};

/**
 * Which definition a drawer edits: where the effective value comes from each time it opens, the
 * way the platform's resource pages open on the definition in use.
 */
export const useEditing = (
  open: boolean,
  from?: string | null
): [string, (v: string) => void] => {
  const [editing, setEditing] = React.useState("core");
  React.useEffect(() => {
    if (open) {
      setEditing(from || "core");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  return [editing, setEditing];
};

/** The saveTo to send: nothing on a gateway with no modes, so it keeps its old behaviour. */
export const saveToParam = (modes: string[], editing: string) =>
  modes.length ? editing : undefined;

const KEBAB = (
  <svg width="1rem" height="1rem" viewBox="0 0 16 16" aria-hidden="true">
    <path
      fill="currentColor"
      fillRule="evenodd"
      d="M9.77 3.64A1.64 1.64 0 1 1 8.14 2a1.63 1.63 0 0 1 1.63 1.64M8.14 6.36a1.64 1.64 0 1 0-.02 3.28 1.64 1.64 0 0 0 .02-3.28m0 4.37a1.64 1.64 0 1 0 1.63 1.63c0-.9-.73-1.63-1.63-1.63"
      clipRule="evenodd"
    />
  </svg>
);

export interface DefsProps {
  // Omitted for a record not saved yet: the picker then chooses where to create it.
  record?: RecordRef;
  defined: string[];
  editing: string;
  onEditing: (c: string) => void;
}

const useDefs = ({ defined, editing }: DefsProps) => {
  const modes = useModes();
  const active = useActiveMode();
  const defs = defined.includes(editing) ? defined : [...defined, editing];
  // The definition this gateway runs on: the active mode's when it has one, else core.
  const inUse = active && defined.includes(active) ? active : "core";
  const label = (c: string) => (c === inUse ? `${c} (active)` : c);
  const free = ["core", ...modes].filter((c) => !defs.includes(c));
  return { modes, defs, label, free };
};

/**
 * The drawer header's kebab, as on the platform's own resource pages: under RESOURCE DEFINITIONS,
 * Create Override (into any mode created here, not only the active one) and Remove Override.
 */
export const DefinitionMenu = (props: DefsProps) => {
  const { record, defined, editing, onEditing } = props;
  const { modes, label, free } = useDefs(props);
  const [create, { isLoading: creating }] = useModeOverrideMutation();
  const [remove] = useModeResetMutation();
  const toasts = useToastNotifications();
  const [asking, setAsking] = React.useState(false);
  const [into, setInto] = React.useState("");
  if (!modes.length || !record) {
    return null;
  }
  const isOverride = editing !== "core" && defined.includes(editing);

  const doCreate = () =>
    create({ ...record, mode: into, from: editing })
      .unwrap()
      .then(() => {
        setAsking(false);
        onEditing(into);
        toasts.notifySuccess(`Override created in ${into}`);
      })
      .catch(errorToast(toasts, "Could not create the override"));

  const doRemove = () =>
    remove({ ...record, mode: editing })
      .unwrap()
      .then(() => {
        toasts.notifySuccess(`${editing} now uses core`);
        onEditing("core");
      })
      .catch(errorToast(toasts, "Could not remove the override"));

  return (
    <>
      <MenuButton
        soloIcon
        buttonColorClass="secondary"
        buttonId="gitcfg-definition-menu"
        buttonDataLabel="Resource definitions"
        menuItems={[
          {
            isSection: true,
            sectionTitle: "RESOURCE DEFINITIONS",
            sectionItems: [
              {
                text: "Create Override",
                disabled: !free.length,
                onClick: () => {
                  setInto(free.find((c) => c !== "core") ?? free[0] ?? "");
                  setAsking(true);
                },
              },
              {
                text: "Remove Override",
                disabled: !isOverride,
                onClick: doRemove,
              },
            ],
          },
        ]}
      >
        {KEBAB}
      </MenuButton>
      <Modal
        open={asking}
        type="confirm"
        title="Create Resource Override?"
        modalConfig={{
          confirmationText: (
            <div className="gitcfg gitcfg-form">
              <p>
                Overriding this resource will create a duplicate version of it
                on the Gateway.
              </p>
              <p>Select a collection for the new definition.</p>
              <SelectInput
                label="Collection"
                value={into}
                values={free.map((c) => ({ label: label(c), value: c }))}
                onChange={(e: unknown) => setInto(selectValue(e) || "")}
              />
              <p className="gitcfg-hint">
                Select a collection for the new resource definition.
              </p>
            </div>
          ),
          primaryText: "Create Override",
          secondaryText: "Cancel",
          primaryDisabled: !into || creating,
        }}
        onClose={() => setAsking(false)}
        onConfirm={doCreate}
      />
    </>
  );
};

/**
 * Which definition the drawer edits: once a record has more than one, "Select Definition to
 * Configure", as the platform shows it. Save writes to the definition selected. For a record not
 * saved yet, the collection to create it in. Renders nothing on a gateway with no modes.
 */
const Definitions = (props: DefsProps) => {
  const { record, editing, onEditing } = props;
  const { modes, defs, label } = useDefs(props);
  if (!modes.length) {
    return null;
  }
  if (!record) {
    return (
      <div className="gitcfg-defs">
        <SelectInput
          label="Collection"
          value={editing}
          values={["core", ...modes].map((c) => ({ label: c, value: c }))}
          onChange={(e: unknown) => onEditing(selectValue(e) || "core")}
        />
        <p className="gitcfg-hint">
          Select a collection for the new resource definition.
        </p>
      </div>
    );
  }
  return (
    <div className="gitcfg-defs">
      {defs.length > 1 ? (
        <>
          <p className="gitcfg-defs-title">Deployment Modes</p>
          <p>
            This resource has <b>{defs.length}</b> definition(s).
          </p>
          <SelectInput
            label="Select Definition to Configure"
            value={editing}
            values={defs.map((c) => ({ label: label(c), value: c }))}
            onChange={(e: unknown) => onEditing(selectValue(e) || "core")}
          />
        </>
      ) : (
        <p className="gitcfg-hint">
          Definition: <b>{label(editing)}</b>. Create Override (⋮) gives a
          deployment mode its own.
        </p>
      )}
    </div>
  );
};

export default Definitions;
