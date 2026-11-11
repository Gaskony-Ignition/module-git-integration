import React, { useState } from "react";
import { SettingsGw, Refresh } from "@inductiveautomation/ignition-icons";
import { Button, PageHeader, useToastNotifications } from "../../webui";
import {
  useGetRemoteQuery,
  useGetStatusQuery,
  usePushMutation,
  useRenameMasterMutation,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import ConfigDrawer from "./ConfigDrawer";

// The Versioning page header. A primary "Configure versioning" button (trailing cog) opens the
// lateral configuration drawer; "Remote sync" pushes to the remote, with bold text showing how
// many local commits are not yet on the remote. The Sync button is primary only when diverged,
// secondary when up to date. Syncing is strictly manual. The remote query polls so the unsynced
// count stays current as config auto-commits happen.
const RemoteSync = ({ initialized }: { initialized: boolean }) => {
  const { data: remote } = useGetRemoteQuery(undefined, {
    skip: !initialized,
    pollingInterval: 15000,
  });
  const [push, { isLoading: pushing }] = usePushMutation();
  const { data: status } = useGetStatusQuery();
  // Shown disabled with the reason, never hidden, when the Git policy turns pushing off.
  const pushRefused = status?.policy?.refused?.PUSH;
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [rename, { isLoading: renaming }] = useRenameMasterMutation();
  const switchRefused = status?.policy?.refused?.BRANCH_SWITCH;
  const toasts = useToastNotifications();

  const configured = !!(remote && remote.configured);
  const ahead = remote?.ahead ?? 0;
  const unsynced = configured && ahead > 0;

  const headerActions = initialized ? (
    <div className="gitcfg-header-actions">
      {configured ? (
        <>
          {unsynced ? (
            <span className="gitcfg-sync-status gitcfg-sync-off">
              {ahead} change{ahead === 1 ? "" : "s"} not synced
            </span>
          ) : (
            <span className="gitcfg-sync-status gitcfg-sync-ok">
              ✓ Up to date
            </span>
          )}
          {pushRefused ? (
            // The short form: the full refusal text squeezed the buttons into four lines.
            <span className="gitcfg-sync-status gitcfg-sync-off">
              Push off{" "}
              {status?.policy?.mode
                ? `in ${status.policy.mode}`
                : "on this gateway"}
            </span>
          ) : null}
          {remote?.renameMaster ? (
            // A repository from before 4.2.0 sits on master while the remote's branch is main.
            <Button
              colorClass="secondary"
              disabled={renaming || !!switchRefused}
              onClick={() =>
                rename({ repo: "config" })
                  .unwrap()
                  .then((r) =>
                    toasts.notifySuccess(`Local master is now ${r.branch}`)
                  )
                  .catch(errorToast(toasts, "Could not rename the branch"))
              }
            >
              {`Rename local master to ${remote.renameMaster}`}
            </Button>
          ) : null}
          <Button
            colorClass={unsynced && !pushRefused ? "primary" : "secondary"}
            startIcon={<Refresh width={18} height={18} />}
            disabled={pushing || !!pushRefused}
            onClick={() =>
              push()
                .unwrap()
                .then((r) =>
                  toasts.notifySuccess(`Pushed ${r.from} to ${r.to}`)
                )
                .catch(errorToast(toasts, "Remote sync failed"))
            }
          >
            {pushing
              ? "Syncing…"
              : remote?.pushFrom && remote.pushTo
              ? `Remote sync (${remote.pushFrom} → ${remote.pushTo})`
              : "Remote sync"}
          </Button>
        </>
      ) : null}
      <Button
        colorClass="primary"
        endIcon={<SettingsGw width={18} height={18} />}
        onClick={() => setDrawerOpen(true)}
      >
        Configure versioning
      </Button>
    </div>
  ) : null;

  return (
    <>
      <PageHeader pageTitle="Versioning" customContent={headerActions} />
      {initialized ? (
        <ConfigDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)} />
      ) : null}
    </>
  );
};

export default RemoteSync;
