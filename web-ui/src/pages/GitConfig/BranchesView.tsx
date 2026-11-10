import React from "react";
import {
  Button,
  Chip,
  Loading,
  Modal,
  useToastNotifications,
} from "../../webui";
import {
  BranchGraphResp,
  GraphEdge,
  GraphRef,
  useCheckoutBranchMutation,
  useGetBranchGraphQuery,
  useGetProjectsQuery,
  useGetRemoteQuery,
  useGetStatusQuery,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import ConfigDrawer from "./ConfigDrawer";

const ROW = 26;
const LANE = 16;
const R = 4.5;

// One colour per lane, from the platform's tokens. Lane 0 is the main line.
const LANE_COLOURS = [
  "var(--primary)",
  "var(--success)",
  "var(--warning-dark)",
  "var(--error)",
  "var(--neutral-70)",
  "var(--primary-70)",
];
const colour = (lane: number) => LANE_COLOURS[lane % LANE_COLOURS.length];

const x = (lane: number) => lane * LANE + LANE / 2 + 2;
const y = (row: number) => row * ROW + ROW / 2;

// A line leaves its commit, runs down its own column and joins the parent's.
const edgePath = (e: GraphEdge): string => {
  const x1 = x(e.fromLane);
  const y1 = y(e.from);
  const x2 = x(e.toLane);
  const y2 = y(e.to);
  if (e.fromLane === e.toLane && e.lane === e.fromLane) {
    return `M${x1} ${y1}V${y2}`;
  }
  const xl = x(e.lane);
  if (e.to === e.from + 1) {
    return `M${x1} ${y1}C${x1} ${y1 + ROW / 2} ${x2} ${
      y2 - ROW / 2
    } ${x2} ${y2}`;
  }
  return (
    `M${x1} ${y1}C${x1} ${y1 + ROW / 2} ${xl} ${y1 + ROW / 2} ${xl} ${
      y1 + ROW
    }` +
    `V${y2 - ROW}` +
    `C${xl} ${y2 - ROW / 2} ${x2} ${y2 - ROW / 2} ${x2} ${y2}`
  );
};

const age = (iso: string): string => {
  const t = Date.parse(iso);
  if (isNaN(t)) return "";
  const s = Math.max(0, (Date.now() - t) / 1000);
  if (s < 3600) return `${Math.max(1, Math.round(s / 60))} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  if (s < 86400 * 60) return `${Math.round(s / 86400)} d ago`;
  return `${Math.round(s / (86400 * 30))} mo ago`;
};

const headline = (g: BranchGraphResp, project?: string): React.ReactNode => {
  const b = <strong>{g.branch || "a detached commit"}</strong>;
  if (g.layout === "combined") {
    return (
      <>Branches are gateway-wide: every project and the config are on {b}.</>
    );
  }
  if (g.scope === "config") {
    return <>The gateway config is on {b}.</>;
  }
  return (
    <>
      Everyone editing {project ? "this project" : "it"} on this gateway is on{" "}
      {b}.
    </>
  );
};

interface Props {
  project?: string;
  config?: boolean;
  // In a drawer: less height to spare than on the page.
  compact?: boolean;
  // Opens where this project's own remote is set (Separate); the gateway's is opened here.
  onConfigureRemote?: () => void;
}

// Read-only commit graph and branch list for one repository, plus a switch for admins. The lanes
// come from the gateway, so this only draws.
const BranchesView = ({
  project,
  config,
  compact,
  onConfigureRemote,
}: Props) => {
  const {
    data: g,
    isFetching,
    error,
  } = useGetBranchGraphQuery({
    project,
    config,
  });
  const [checkout, { isLoading: switching }] = useCheckoutBranchMutation();
  const { data: status } = useGetStatusQuery();
  // The Git policy can turn branch switching off; the buttons stay, disabled, with the reason.
  const switchRefused = status?.policy?.refused?.BRANCH_SWITCH;
  const toasts = useToastNotifications();
  const { data: remote } = useGetRemoteQuery();
  const { data: projects } = useGetProjectsQuery(undefined, {
    skip: !project,
  });
  const [configOpen, setConfigOpen] = React.useState(false);
  const [target, setTarget] = React.useState<string | null>(null);
  const [refusal, setRefusal] = React.useState<{
    message: string;
    files: string[];
  } | null>(null);

  if (isFetching && !g) return <Loading isLoading={true} />;
  if (!g) {
    return (
      <p className="gitcfg-empty">
        {(error as any)?.data?.error || "No branch graph for this repository."}
      </p>
    );
  }

  const combined = g.layout === "combined";
  const byCommit = new Map<string, GraphRef[]>();
  g.refs.forEach((r) => {
    byCommit.set(r.sha, [...(byCommit.get(r.sha) || []), r]);
  });
  const localNames = new Set(
    g.refs.filter((r) => r.type === "local").map((r) => r.name)
  );
  const width = Math.max(1, g.lanes) * LANE + 6;
  const height = g.commits.length * ROW;

  const doSwitch = () => {
    const branch = target;
    setTarget(null);
    if (!branch) return;
    setRefusal(null);
    checkout({ project, config, branch })
      .unwrap()
      .then((r) => {
        if (r.ok) toasts.notifySuccess(r.message);
        else setRefusal({ message: r.message, files: r.files || [] });
      })
      .catch(errorToast(toasts, "Could not switch branch"));
  };

  const chip = (r: GraphRef) => {
    // A remote branch with a local twin is the same switch; keep the chip a label.
    const canSwitch =
      r.type !== "tag" &&
      !r.head &&
      !(r.type === "remote" && localNames.has(r.name.replace(/^[^/]+\//, "")));
    const label = `${r.head ? "HEAD › " : ""}${r.name}`;
    return (
      <span key={`${r.type}-${r.name}`} className="gitcfg-ref-chip">
        {canSwitch ? (
          <button
            type="button"
            className={`gitcfg-ref gitcfg-ref-${r.type}`}
            disabled={switching || !!switchRefused}
            onClick={() => setTarget(r.name)}
          >
            {label}
          </button>
        ) : (
          <span
            className={`gitcfg-ref gitcfg-ref-${r.type}${
              r.head ? " is-head" : ""
            }`}
          >
            {label}
          </span>
        )}
      </span>
    );
  };

  const badges = (b: BranchGraphResp["branches"][number]) => (
    <>
      {b.gone ? <Chip colorClass="error">gone</Chip> : null}
      {b.merged ? <Chip colorClass="success">merged</Chip> : null}
      {b.ahead > 0 ? <Chip alt>{`↑${b.ahead}`}</Chip> : null}
      {b.behind > 0 ? <Chip colorClass="info">{`↓${b.behind}`}</Chip> : null}
      {b.protectedReason && b.protectedReason !== "current" ? (
        <Chip alt>{b.protectedReason}</Chip>
      ) : null}
    </>
  );

  // The gateway repository's remote (config, or everything in Combined), else the project's own.
  const gatewayRepo = config || combined;
  const hasRemote = gatewayRepo
    ? !!remote?.configured
    : !!projects?.projects.find((p) => p.name === project)?.remoteUrl;
  const configureRemote =
    gatewayRepo || !onConfigureRemote
      ? () => setConfigOpen(true)
      : onConfigureRemote;

  return (
    <div className="gitcfg-branches">
      {!hasRemote ? (
        <div className="gitcfg-no-remote">
          <p>
            No remote is set, so there are no remote branches here, and nothing
            to pull or push.
          </p>
          <Button colorClass="secondary" onClick={configureRemote}>
            {gatewayRepo
              ? "Set the gateway remote"
              : "Set this project's remote"}
          </Button>
        </div>
      ) : null}
      {gatewayRepo ? (
        <ConfigDrawer open={configOpen} onClose={() => setConfigOpen(false)} />
      ) : null}
      <p className="gitcfg-branches-head">
        {headline(g, project)}
        {g.filtered ? (
          <span className="gitcfg-hint">
            {" "}
            Commits that leave {project} alone are dimmed; they are the
            gateway&apos;s history.
          </span>
        ) : null}
      </p>
      {switchRefused ? <p className="gitcfg-hint">{switchRefused}</p> : null}

      {refusal ? (
        <div className="gitcfg-refusal" role="alert">
          <p>{refusal.message}</p>
          {refusal.files.length ? (
            <ul>
              {refusal.files.map((f) => (
                <li key={f}>
                  <code>{f}</code>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      <div className={`gitcfg-branches-body${compact ? " is-compact" : ""}`}>
        <div className="gitcfg-graph-pane">
          <div className="gitcfg-graph" style={{ height }}>
            <svg width={width} height={height} aria-hidden="true">
              {g.edges.map((e, i) => (
                <path
                  key={i}
                  d={edgePath(e)}
                  fill="none"
                  strokeWidth={2}
                  style={{
                    stroke: colour(e.lane),
                    opacity: g.commits[e.from].touches ? 1 : 0.3,
                  }}
                />
              ))}
              {g.commits.map((c, i) => (
                <circle
                  key={c.sha}
                  cx={x(c.lane)}
                  cy={y(i)}
                  r={c.sha === g.headSha ? R + 1.5 : R}
                  strokeWidth={c.sha === g.headSha ? 2 : 0}
                  style={{
                    fill: colour(c.lane),
                    stroke: "var(--neutral-100)",
                    opacity: c.touches ? 1 : 0.3,
                  }}
                />
              ))}
            </svg>
            <ol className="gitcfg-graph-rows" style={{ marginLeft: width }}>
              {g.commits.map((c) => (
                <li
                  key={c.sha}
                  className={c.touches ? "" : "is-dim"}
                  style={{ height: ROW }}
                >
                  {(byCommit.get(c.sha) || []).map(chip)}
                  <span className="gitcfg-graph-subject">{c.subject}</span>
                  <span className="gitcfg-meta">
                    {c.shortSha} · {c.author} · {age(c.time)}
                  </span>
                </li>
              ))}
            </ol>
          </div>
          {g.truncated ? (
            <p className="gitcfg-hint">
              Showing the latest {g.commits.length} commits.
            </p>
          ) : null}
        </div>

        <div className="gitcfg-branch-list">
          <table className="gitcfg-table">
            <thead>
              <tr>
                <th>Branch</th>
                <th>Last commit</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {g.branches.map((b) => {
                const current = b.name === g.branch;
                return (
                  <tr key={b.name}>
                    <td>
                      <span className="gitcfg-proj-name">
                        {current ? "● " : ""}
                        {b.name}
                      </span>
                      <span className="gitcfg-proj-title">
                        {b.upstream || "no upstream"}
                      </span>
                      <span className="gitcfg-badges">{badges(b)}</span>
                    </td>
                    <td>
                      <span className="gitcfg-graph-subject">{b.subject}</span>
                      <span className="gitcfg-proj-title">{age(b.time)}</span>
                    </td>
                    <td className="gitcfg-table-act">
                      <Button
                        colorClass="secondary"
                        disabled={current || switching || !!switchRefused}
                        onClick={() => setTarget(b.name)}
                      >
                        {switchRefused && !current
                          ? "Switch (not allowed by policy)"
                          : "Switch to this branch"}
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      <Modal
        open={target !== null}
        type="confirm"
        title={`Switch to ${target ?? ""}`}
        modalConfig={{
          confirmationText: combined
            ? "Switches the whole gateway: config and every project. Open Designers pick it up on their next refresh."
            : config
            ? "Switches the gateway config."
            : `Switches ${project} for everyone editing it on this gateway. Open Designers pick it up on their next refresh.`,
          primaryText: "Switch",
          secondaryText: "Cancel",
          warningGeneral: true,
        }}
        onClose={() => setTarget(null)}
        onConfirm={doSwitch}
      />
    </div>
  );
};

export default BranchesView;
