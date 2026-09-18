import React from "react";
import { Button, useToastNotifications } from "../../webui";
import { useClearEventsMutation, useGetEventsQuery } from "./GitConfig.service";
import { errorToast } from "./errors";

// Every delivery, sync, commit and push since the gateway started. An unattended sync or runner
// delivery that failed is reported here and nowhere else.
const Logs = () => {
  const { data } = useGetEventsQuery(undefined, { pollingInterval: 10000 });
  const [clear] = useClearEventsMutation();
  const toasts = useToastNotifications();
  const log = data?.log ?? [];
  const stats = data?.stats;

  return (
    <div>
      <div className="gitcfg-page-head">
        <div>
          <h3>Logs</h3>
          <p>
            The last 50 git events since the gateway started
            {stats
              ? ` · ${stats.fired} ${stats.fired === 1 ? "event" : "events"}, `
              : ""}
            {stats ? (
              <span className={stats.failures > 0 ? "gitcfg-err" : ""}>
                {stats.failures} failed
              </span>
            ) : null}
          </p>
        </div>
        <div className="gitcfg-actions">
          <Button
            colorClass="secondary"
            onClick={() =>
              clear()
                .unwrap()
                .catch(errorToast(toasts, "Could not clear the log"))
            }
          >
            Clear
          </Button>
        </div>
      </div>
      {log.length === 0 ? (
        <p className="gitcfg-empty">Nothing yet.</p>
      ) : (
        <div className="gitcfg-table-scroll">
          <table className="gitcfg-table">
            <thead>
              <tr>
                <th>When</th>
                <th>Event</th>
                <th>Where</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {log.map((e, i) => {
                const details = [
                  e.user,
                  e.commit ? e.commit.slice(0, 7) : "",
                  e.fileCount > 0 ? `${e.fileCount} file(s)` : "",
                  e.remote,
                ]
                  .filter((v) => v)
                  .join(" · ");
                return (
                  <tr key={`${e.timestamp}-${i}`}>
                    <td className="gitcfg-meta">
                      {e.timestamp.replace("T", " ").replace(/\..*$/, "")}
                    </td>
                    <td>
                      <span
                        className={
                          e.outcome === "failure" ? "gitcfg-err" : "gitcfg-ok"
                        }
                      >
                        {e.type}
                      </span>
                      {e.message ? (
                        <span className="gitcfg-proj-title">{e.message}</span>
                      ) : null}
                    </td>
                    <td>
                      {e.scope === "config"
                        ? "gateway config"
                        : `${e.project}${e.branch ? ` · ${e.branch}` : ""}`}
                    </td>
                    <td className="gitcfg-meta gitcfg-proj-remote">
                      {details}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default Logs;
