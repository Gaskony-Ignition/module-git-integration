import React from "react";
import { useDispatch } from "react-redux";
import baseApi from "../../api/index";
import { useToastNotifications } from "../../webui";
import { errorToast } from "./errors";
import { useOverrideAuditMutation } from "./GitConfig.service";

type Record = "policy" | "remote" | "runner";

/**
 * What a platform singleton page needs from this module. Save Changes goes through the module's
 * own route, not the platform's PUT, so a save keeps what that route does: validation, the secret
 * rules, the host checks, the config commit and the audit row. The Resource Definitions actions
 * stay the platform's own; after each, the module records the audit row and drops its cached
 * reads, which the platform's calls do not know about.
 */
export const useSingletonSave = (
  type: Record,
  send: (config: { [k: string]: unknown }, saveTo: string) => Promise<unknown>
) => {
  const toasts = useToastNotifications();
  const dispatch = useDispatch();
  const [audit] = useOverrideAuditMutation();
  const refresh = () =>
    dispatch(
      baseApi.util.invalidateTags([
        "status",
        "remote",
        "runner",
        "projects",
        "definition",
      ])
    );
  const after =
    (action: "create" | "move" | "remove") =>
    async (args: {
      fromCollection?: string;
      toCollection?: string;
      collection?: string;
    }) => {
      await audit({
        type,
        action,
        from: args.fromCollection ?? args.collection ?? "",
        to: args.toCollection ?? "",
      })
        .unwrap()
        .catch(() => undefined);
      refresh();
    };
  return {
    handleSubmit: async ({
      formData,
      refetch,
      setSubmitDisabled,
    }: {
      formData: { config: { [k: string]: unknown }; collection: string };
      refetch: () => void;
      setSubmitDisabled: (d: boolean) => void;
    }) => {
      setSubmitDisabled(true);
      try {
        await send(formData.config ?? {}, formData.collection || "core");
        refetch();
        refresh();
        toasts.notifySuccess("Changes saved", true);
      } catch (e) {
        errorToast(toasts, "Could not save the changes")(e);
        setSubmitDisabled(false);
      }
    },
    callbacks: {
      onCopyResource: after("create"),
      onMoveResource: after("move"),
      onDeleteResource: after("remove"),
    },
  };
};

/** While a singleton tab is showing: hides the platform menu's Add Backup Version (see styles). */
export const useNoBackupMenu = () =>
  React.useEffect(() => {
    document.body.classList.add("gitcfg-no-backup");
    return () => document.body.classList.remove("gitcfg-no-backup");
  }, []);
