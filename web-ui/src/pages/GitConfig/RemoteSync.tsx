import React, { useState } from "react";
import { SettingsGw, Refresh } from "@inductiveautomation/ignition-icons";
import { Button, Modal, PageHeader, useToastNotifications } from "../../webui";
import {
  useAbortMergeMutation,
  useFetchConfigMutation,
  useJoinConfigMutation,
  usePullConfigMutation,
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
  const layoutCombined = status?.layout === "combined";
  // Shown disabled with the reason, never hidden, when the Git policy turns pushing off.
  const pushRefused = status?.policy?.refused?.PUSH;
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [rename, { isLoading: renaming }] = useRenameMasterMutation();
  const switchRefused = status?.policy?.refused?.BRANCH_SWITCH;
  const toasts = useToastNotifications();
  const [fetchConfig, { isLoading: fetching }] = useFetchConfigMutation();
  const [pull, { isLoading: pulling }] = usePullConfigMutation();
  const [join, { isLoading: joining }] = useJoinConfigMutation();
  const [confirmJoin, setConfirmJoin] = useState(false);
  const pullRefused = status?.policy?.refused?.["delivery:pull"];
  const [abortMerge, { isLoading: aborting }] = useAbortMergeMutation();
  const [confirmAbort, setConfirmAbort] = useState(false);

  const configured = !!(remote && remote.configured);
  const ahead = remote?.ahead ?? 0;
  const unsynced = configured && ahead > 0;

  const headerActions = initialized ? (
    <div className="gitcfg-header-actions">
      {status?.midOperation ? (
        <>
          <span
            className="gitcfg-sync-status gitcfg-sync-off"
            title={status.midOperation}
          >
            Merge in progress
          </span>
          <Button
            colorClass="secondary"
            disabled={aborting}
            onClick={() => setConfirmAbort(true)}
          >
            {aborting ? "Aborting…" : "Abort merge…"}
          </Button>
        </>
      ) : null}
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
            colorClass="secondary"
            disabled={fetching}
            onClick={() =>
              fetchConfig().unwrap().catch(errorToast(toasts, "Fetch failed"))
            }
          >
            {fetching ? "Fetching…" : "Fetch"}
          </Button>
          {remote?.unrelated ? (
            <Button
              colorClass="primary"
              disabled={joining || !!switchRefused}
              title={switchRefused || undefined}
              onClick={() => setConfirmJoin(true)}
            >
              Join remote history…
            </Button>
          ) : (
            <Button
              colorClass={(remote?.behind ?? 0) > 0 ? "primary" : "secondary"}
              disabled={pulling || !!pullRefused}
              title={pullRefused || undefined}
              onClick={() =>
                pull()
                  .unwrap()
                  .then((r) =>
                    toasts.notifySuccess(
                      r.changed ? `Pulled: ${r.message}` : "Already up to date"
                    )
                  )
                  .catch(errorToast(toasts, "Pull failed"))
              }
            >
              {pulling
                ? "Pulling…"
                : (remote?.behind ?? 0) > 0
                ? `Pull (${remote?.behind})`
                : "Pull"}
            </Button>
          )}
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
      <Modal
        open={confirmAbort}
        type="confirm"
        title="Abort merge"
        modalConfig={{
          confirmationText:
            "A pull stopped on conflicts and its merge is still open in the gateway repository, so config is not being committed. Aborting puts back the files the merge changed, as they were before it, and drops any conflict resolutions made so far. Other uncommitted work is kept. To finish the merge instead, open the project in the Designer and pull again.",
          primaryText: "Abort merge",
          secondaryText: "Cancel",
          warningGeneral: true,
        }}
        onClose={() => setConfirmAbort(false)}
        onConfirm={() => {
          setConfirmAbort(false);
          abortMerge()
            .unwrap()
            .then(() => toasts.notifySuccess("Merge aborted"))
            .catch(errorToast(toasts, "Could not abort the merge"));
        }}
      />
      <Modal
        open={confirmJoin}
        type="confirm"
        title="Join remote history"
        modalConfig={{
          confirmationText: `The remote branch ${
            remote?.pushTo ?? ""
          } started from another gateway, so it shares no history with this one and push and pull refuse it. Joining moves this gateway's branch onto the remote's latest commit and records this gateway's config, exactly as it is now, as one commit on top. Nothing on this gateway changes; the next push sends that commit. Refused if the remote's layout does not match.${
            remote?.joinDeletesCount
              ? `\n\nThis commit also deletes ${
                  remote.joinDeletesCount
                } file(s) the remote branch has outside config/${
                  layoutCombined ? " and projects/" : ""
                } that this gateway does not:\n${(
                  remote.joinDeletes ?? []
                ).join("\n")}${
                  remote.joinDeletesCount > (remote.joinDeletes ?? []).length
                    ? `\n… and ${
                        remote.joinDeletesCount -
                        (remote.joinDeletes ?? []).length
                      } more`
                    : ""
                }`
              : ""
          }`,
          primaryText: joining ? "Joining…" : "Join",
          secondaryText: "Cancel",
          warningGeneral: true,
        }}
        onClose={() => setConfirmJoin(false)}
        onConfirm={() => {
          setConfirmJoin(false);
          join()
            .unwrap()
            .then((r) =>
              toasts.notifySuccess(`Joined: ${r.commit.slice(0, 7)} on top`)
            )
            .catch(errorToast(toasts, "Could not join the remote history"));
        }}
      />
    </>
  );
};

export default RemoteSync;
