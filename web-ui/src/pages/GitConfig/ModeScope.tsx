import React from "react";
import { Button, useToastNotifications } from "../../webui";
import { SelectInput } from "./fields";
import { selectValue } from "./selectValue";
import { errorToast } from "./errors";
import {
  ModeResetReq,
  useGetStatusQuery,
  useModeResetMutation,
} from "./GitConfig.service";

/** The active deployment mode, or null when the gateway runs on core alone. */
export const useActiveMode = (): string | null => {
  const { data } = useGetStatusQuery();
  return data?.policy?.mode ?? null;
};

/** "from core" / "from Prod": which collection a record's value comes from. */
export const FromTag = ({ from }: { from?: string | null }) =>
  from ? (
    <span className={`gitcfg-from${from === "core" ? "" : " is-mode"}`}>
      from {from}
    </span>
  ) : null;

/**
 * Where a drawer's Save writes: "core" or the active mode. Starts at where the record is defined
 * now each time the drawer opens, so a save without a choice changes nothing about where it lives.
 */
export const useSaveTo = (
  open: boolean,
  from?: string | null
): [string, (v: string) => void] => {
  const mode = useActiveMode();
  const [saveTo, setSaveTo] = React.useState("core");
  React.useEffect(() => {
    if (open) {
      setSaveTo(mode && from === mode ? mode : "core");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  return [saveTo, setSaveTo];
};

/** The saveTo to send: nothing when no mode is active, so the gateway keeps its old behaviour. */
export const saveToParam = (mode: string | null, saveTo: string) =>
  mode ? saveTo : undefined;

/**
 * Inside a drawer: where the value comes from now, where Save will write it, and Reset to core when
 * the active mode overrides it. Renders only the tag when no mode is active.
 */
const ModeScope = ({
  from,
  saveTo,
  onSaveTo,
  reset,
}: {
  from?: string | null;
  saveTo: string;
  onSaveTo: (v: string) => void;
  // Omitted for a record not saved yet.
  reset?: ModeResetReq;
}) => {
  const mode = useActiveMode();
  const [doReset, { isLoading }] = useModeResetMutation();
  const toasts = useToastNotifications();
  if (!mode) {
    return null;
  }
  return (
    <div className="gitcfg-mode-scope">
      <SelectInput
        label="Save to"
        value={saveTo}
        values={[
          { label: "core (every mode without its own)", value: "core" },
          { label: `${mode} only`, value: mode },
        ]}
        onChange={(e: unknown) => onSaveTo(selectValue(e) || "core")}
      />
      <p className="gitcfg-hint">
        Now <FromTag from={from || "core"} />.
        {from === mode && reset ? (
          <>
            {" "}
            <Button
              colorClass="secondary"
              disabled={isLoading}
              onClick={() =>
                doReset(reset)
                  .unwrap()
                  .then(() => toasts.notifySuccess(`${mode} now uses core`))
                  .catch(errorToast(toasts, "Could not reset to core"))
              }
            >
              Reset to core
            </Button>
          </>
        ) : null}
      </p>
    </div>
  );
};

export default ModeScope;
