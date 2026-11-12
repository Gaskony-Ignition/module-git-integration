import baseApi from "../../api/index";
import { BASE } from "../../config";

/** Sync now's answer: "failed" or "warning" (ran, did not pull) are not shown as success. */
export interface SyncNowResult {
  ok: boolean;
  outcome: "ok" | "warning" | "failed";
  result: string;
}

export interface ConfigChange {
  path: string;
  type: string;
}
export interface StatusResp {
  initialized: boolean;
  // Set while a merge is open in the gateway repository (a Designer left a conflict): why.
  midOperation?: string;
  dirty: boolean;
  changes: ConfigChange[];
  // The installed module's version, e.g. "3.7.0". Shown under the page title.
  version?: string;
  // Separate: a repository per project. Combined: one gateway repository (4.0.0).
  layout?: Layout;
  policy?: Policy;
  // Every deployment mode created on this gateway, active or not.
  modes?: string[];
}
// The gateway's Git policy (4.1.0) and what it refuses in the active deployment mode.
export type DeliveryKind = "off" | "pull" | "replace" | "runner";
export interface PolicyFields {
  pushAllowed: boolean;
  designerCommit: boolean;
  branchSwitch: boolean;
  layoutSwitch: boolean;
  forcePushAllowed: boolean;
  // Local folders, file://, git://, http:// and local-network hosts (4.3.0, off by default).
  allowInsecureRemotes: boolean;
  // Deliveries install only tips signed by GitHub's web-flow key (4.4.0, off by default).
  githubMergedOnly: boolean;
  // The hosts whose web-flow signature counts (github.com, or GitHub Enterprise hosts).
  githubHosts: string[];
  deliveryModes: DeliveryKind[];
  allowedRemoteHosts: string[];
}
export interface Policy extends PolicyFields {
  // The active deployment mode, null when the gateway runs on core alone.
  mode: string | null;
  // The collection the policy comes from: "core" or a mode name.
  from: string | null;
  // Refusal text by action: PUSH, FORCE_PUSH, DESIGNER_COMMIT, BRANCH_SWITCH, LAYOUT_SWITCH,
  // and "delivery:<kind>".
  refused: Record<string, string>;
  // The collections defining it, core first: ["core", "Prod"].
  defined?: string[];
}
// Which audit profile essential actions are written to (4.4.0). profile "" follows the gateway's.
export interface AuditSettings {
  enabled: boolean;
  profile: string;
  gatewayHasProfile: boolean;
  profiles: string[];
}
// Where a save lands: "core" or the active mode. Left out, a record is saved where it is defined.
export interface SaveTo {
  saveTo?: string;
}
// Which record a drawer edits, for the definition routes.
export type RecordRef =
  | { type: "policy" | "remote" | "runner" }
  | { type: "sync"; project: string }
  | { type: "credential"; id: number; credType: "SSH" | "HTTPS" };
// Remove Override: deletes `mode`'s definition (the active mode's when left out).
export type ModeResetReq = RecordRef & { mode?: string };
// Create Override: copies the definition in `from` (core when left out) into `mode`.
export type ModeOverrideReq = RecordRef & { mode: string; from?: string };
// One record's definition in one collection, in the shape of the record's own GET.
export type DefinitionResp = Record<string, unknown> & {
  collection: string;
  defined: string[];
};
const withSaveTo = (path: string, saveTo?: string) =>
  saveTo ? `${path}?saveTo=${encodeURIComponent(saveTo)}` : path;
export type Layout = "separate" | "combined";
// The gateway repository's delivery. pull and runner only work in the Combined layout.
export type ConfigDeliveryMode = "off" | "replace" | "pull" | "runner";
export interface Commit {
  hash: string;
  shortHash: string;
  author: string;
  date: string;
  message: string;
  refs: string;
}
export interface HistoryResp {
  commits: Commit[];
  remoteConfigured: boolean;
  // Full hashes the local / remote branch tips point at ("" when none).
  localHead: string;
  remoteHead: string;
  hasMore: boolean;
}
export interface CommitFile {
  changeType: string;
  path: string;
}
export interface CommitFilesResp {
  files: CommitFile[];
}
export interface FileDiffResp {
  old: string;
  new: string;
}
export interface CredentialOption {
  id: number;
  type: "SSH" | "HTTPS";
  label: string;
  // The collection this credential comes from: "core" or a mode name.
  from?: string | null;
  defined?: string[];
  // Prefill for editing: an SSH key's name; an HTTPS credential's host and username.
  name?: string;
  host?: string;
  username?: string;
  // Null until the gateway's first check of it finishes.
  check: CredentialCheck | null;
}
export interface CredentialCheck {
  checkedAt: number;
  account?: string | null;
  // ISO date, "never", or null when the host cannot say (SSH keys, hosts other than GitHub).
  expires?: string | null;
  // The host refused the token outright: expired or revoked.
  rejected: boolean;
  // Why the host could not be asked. Shown in place of the expiry.
  error?: string | null;
  // What the token may touch at all, which is not the same as what this gateway uses it for.
  scope?: {
    kind: "classic" | "fine-grained";
    summary: string;
    repos: string[];
  } | null;
  reach: {
    target: string;
    read: boolean;
    push: boolean;
    error?: string | null;
  }[];
}
export interface RemoteResp {
  configured: boolean;
  // Commits on the fetched remote branch not here; whether it shares no history (4.4.0).
  behind?: number;
  unrelated?: boolean;
  // Set when the repository's lone local branch is master and the remote's is this name.
  renameMaster?: string | null;
  from?: string | null;
  defined?: string[];
  uri?: string;
  branch?: string;
  // Secret prefill (the embedded secret itself is never returned).
  secretMode?: "inline" | "reference";
  username?: string;
  providerName?: string;
  secretName?: string;
  // Local commits not yet on the remote (0 = up to date).
  ahead?: number;
  // Remote sync pushes the checked-out branch (pushFrom) to pushTo on the remote.
  pushFrom?: string;
  pushTo?: string;
  lastPush?: { time: number; ok: boolean; error?: string };
  // Follow a remote branch: the config is replaced by it on a timer (3.10.0).
  delivery?: {
    mode: ConfigDeliveryMode;
    branch: string;
    intervalSeconds: number;
    last?: { time: number; ok: boolean; message: string; commit: string };
  };
}
// Inline secret fields shared by save/test requests.
export interface RemoteSecretReq {
  uri: string;
  mode: "inline" | "reference";
  key?: string; // embedded SSH private key
  password?: string; // embedded HTTPS password/token
  username?: string; // HTTPS username
  providerName?: string; // referenced
  secretName?: string; // referenced
}
export interface SecretProvider {
  name: string;
  secrets: string[];
  error?: string;
}
export interface SecretProvidersResp {
  providers: SecretProvider[];
}
// A project on this gateway and its git state. Unversioned projects are listed too — the
// absence is the answer, and a project missing from a list of repos looks like one nobody
// has set up yet.
export interface ProjectStatus {
  name: string;
  title: string;
  versioned: boolean;
  branch?: string | null;
  // Against the branch's upstream; absent when it has none.
  ahead?: number;
  behind?: number;
  remoteName?: string | null;
  remoteUrl?: string | null;
  changes: number;
  error?: string | null;
  // The image-store folder this project versions. Empty (the default) means it versions none.
  imagePrefix?: string;
  // How changes reach this project. "off" refuses the runner and runs no sync.
  delivery: Delivery;
  // False when the runner is switched off or has no token: a runner delivery then delivers nothing.
  runnerEnabled: boolean;
  // Sync settings, also used by a runner repo update.
  syncBranch: string;
  syncIntervalSeconds: number;
  syncUser: string;
  // False when Sync — replace or a release takes ignition/global-props from the branch or zip.
  keepProjectProperties?: boolean;
  // Where the delivery record comes from: "core" or a mode name; null when none is saved.
  syncFrom?: string | null;
  syncDefined?: string[];
  // Set when the project's lone local branch is master and its remote's is this name.
  renameMaster?: string | null;
  // What is wrong with the credential this project pulls with, if anything.
  credentialIssue?: string | null;
}
export type Delivery =
  | "off"
  | "runner-release"
  | "runner-repo"
  | "sync-pull"
  | "sync-replace";
export interface DeliveryReq {
  project: string;
  delivery: Delivery;
  branch?: string;
  intervalSeconds?: number;
  ignitionUser?: string;
  // Absent leaves the stored choice as it is.
  keepProjectProperties?: boolean;
}
export interface GraphCommit {
  sha: string;
  shortSha: string;
  parents: string[];
  author: string;
  time: string;
  subject: string;
  lane: number;
  // False only in a filtered Combined project view: the commit leaves the project alone.
  touches: boolean;
}
export interface GraphEdge {
  from: number;
  fromLane: number;
  to: number;
  toLane: number;
  lane: number;
}
export interface GraphRef {
  name: string;
  type: "local" | "remote" | "tag";
  sha: string;
  head: boolean;
}
export interface GraphBranch {
  name: string;
  upstream?: string | null;
  gone: boolean;
  ahead: number;
  behind: number;
  merged: boolean;
  protectedReason?: string | null;
  sha: string;
  subject: string;
  time: string;
}
export interface BranchGraphResp {
  layout: Layout;
  scope: "project" | "gateway" | "config";
  branch?: string | null;
  headSha?: string | null;
  lanes: number;
  truncated: boolean;
  filtered: boolean;
  commits: GraphCommit[];
  edges: GraphEdge[];
  refs: GraphRef[];
  branches: GraphBranch[];
}
export interface BranchCheckoutResp {
  ok: boolean;
  message: string;
  refused?: "dirty" | "followed" | "missing";
  files?: string[];
  branch?: string;
}
export interface ProjectsResp {
  layout?: Layout;
  projects: ProjectStatus[];
  // Top-level folders in the gateway image store, offered as choices.
  imageFolders?: string[];
}
// Add-credential request: each secret is either typed inline or a Secret Provider reference.
export type AddCredentialReq =
  | { type: "SSH"; name: string; mode: "inline"; key: string }
  | {
      type: "SSH";
      name: string;
      mode: "reference";
      providerName: string;
      secretName: string;
    }
  | {
      type: "HTTPS";
      host: string;
      username: string;
      mode: "inline";
      password: string;
    }
  | {
      type: "HTTPS";
      host: string;
      username: string;
      mode: "reference";
      providerName: string;
      secretName: string;
    };

export interface TreeEntry {
  name: string;
  path: string;
  directory: boolean;
  excluded: boolean;
  // Tracked files stay tracked whatever .gitignore says, so the two are reported separately.
  tracked: boolean;
  // The .gitignore line that decided it, null when nothing matched.
  rule: string | null;
  // True when `rule` is this path's own line, so unticking can delete it rather than negate.
  ownRule: boolean;
  // Folders only: INCLUDED | EXCLUDED | MIXED | UNKNOWN (roll-up budget spent).
  childState: string | null;
  // False when an excluded ANCESTOR settled it — git cannot re-include below an excluded
  // directory, so the row must not offer a tick that would do nothing.
  reincludable: boolean;
}
export interface TreeResp {
  path: string;
  entries: TreeEntry[];
}
export interface TreeSearchResp {
  entries: TreeEntry[];
  // The walk hit its budget or the result cap — the list is a partial answer, and says so.
  truncated: boolean;
}
export interface IgnoreResp {
  text: string;
}
export interface IgnoreEditReq {
  exclude?: string[];
  include?: string[];
  text?: string;
}

export interface RunnerConfig {
  enabled: boolean;
  hasToken: boolean;
  from?: string | null;
  defined?: string[];
}
export interface EventLogEntry {
  type: string;
  outcome: string;
  scope: string;
  project: string;
  user: string;
  branch: string;
  remote: string;
  commit: string;
  message: string;
  fileCount: number;
  timestamp: string;
}
export interface EventsResp {
  log: EventLogEntry[];
  stats: {
    fired: number;
    failures: number;
  };
}

export const gitConfigApi = baseApi.injectEndpoints({
  endpoints: (builder) => ({
    getStatus: builder.query<StatusResp, void>({
      query: () => `${BASE}/status`,
      providesTags: ["status"],
    }),
    getHistory: builder.query<HistoryResp, { skip: number; limit: number }>({
      query: ({ skip, limit }) => `${BASE}/history?skip=${skip}&limit=${limit}`,
      providesTags: ["history"],
    }),
    getCommitFiles: builder.query<CommitFilesResp, string>({
      query: (hash) => `${BASE}/commit-files?hash=${encodeURIComponent(hash)}`,
    }),
    // Without a hash the gateway diffs HEAD vs the working tree (uncommitted changes).
    getFileDiff: builder.query<FileDiffResp, { hash?: string; path: string }>({
      query: ({ hash, path }) =>
        hash
          ? `${BASE}/file-diff?hash=${encodeURIComponent(
              hash
            )}&path=${encodeURIComponent(path)}`
          : `${BASE}/file-diff?path=${encodeURIComponent(path)}`,
    }),
    getRemote: builder.query<RemoteResp, void>({
      query: () => `${BASE}/remote`,
      providesTags: ["remote"],
    }),
    getCredentials: builder.query<{ credentials: CredentialOption[] }, void>({
      query: () => `${BASE}/credentials`,
      providesTags: ["credentials"],
    }),
    saveRemote: builder.mutation<
      unknown,
      RemoteSecretReq & { branch: string } & SaveTo
    >({
      query: ({ saveTo, ...body }) => ({
        url: withSaveTo(`${BASE}/remote`, saveTo),
        method: "POST",
        body,
      }),
      // Saving the remote writes (and the gateway commits) the config-remote/credential
      // resources, so refresh the history table too — not just the remote indicator.
      invalidatesTags: ["definition", "remote", "history"],
    }),
    removeRemote: builder.mutation<unknown, void>({
      query: () => ({ url: `${BASE}/remote-remove`, method: "POST", body: {} }),
      invalidatesTags: ["remote", "history"],
    }),
    testRemote: builder.mutation<unknown, RemoteSecretReq>({
      query: (body) => ({ url: `${BASE}/remote-test`, method: "POST", body }),
    }),
    fetchConfig: builder.mutation<unknown, void>({
      query: () => ({ url: `${BASE}/config-fetch`, method: "POST", body: {} }),
      invalidatesTags: ["remote", "history"],
    }),
    pullConfig: builder.mutation<
      { ok: boolean; changed: boolean; message: string; files: string[] },
      void
    >({
      query: () => ({ url: `${BASE}/config-pull`, method: "POST", body: {} }),
      invalidatesTags: ["remote", "history", "status", "events"],
    }),
    abortMerge: builder.mutation<{ ok: boolean }, void>({
      query: () => ({ url: `${BASE}/abort-merge`, method: "POST", body: {} }),
      invalidatesTags: ["remote", "history", "status", "events"],
    }),
    joinConfig: builder.mutation<{ commit: string }, void>({
      query: () => ({ url: `${BASE}/config-join`, method: "POST", body: {} }),
      invalidatesTags: ["remote", "history", "status", "events"],
    }),
    push: builder.mutation<{ from: string; to: string }, void>({
      query: () => ({ url: `${BASE}/push`, method: "POST", body: {} }),
      // History too: a push flips commits from local to remote.
      invalidatesTags: ["remote", "history"],
    }),
    // The provider secret the config remote's credential names (WRITE only, so a mutation).
    getSecretRef: builder.mutation<
      { providerName?: string; secretName?: string },
      { collection?: string }
    >({
      query: (body) => ({ url: `${BASE}/secret-ref`, method: "POST", body }),
    }),
    getSecretProviders: builder.query<SecretProvidersResp, void>({
      // POST: the route needs WRITE, and the gateway only grants WRITE to unsafe methods.
      query: () => ({
        url: `${BASE}/secret-providers`,
        method: "POST",
        body: {},
      }),
      providesTags: ["secretProviders"],
    }),
    // With an id, edits that credential in place.
    addCredential: builder.mutation<
      { id: number; type: string },
      AddCredentialReq & { id?: number } & SaveTo
    >({
      query: ({ saveTo, ...body }) => ({
        url: withSaveTo(`${BASE}/credentials`, saveTo),
        method: "POST",
        body,
      }),
      invalidatesTags: ["definition", "credentials"],
    }),
    removeCredential: builder.mutation<
      unknown,
      { type: "SSH" | "HTTPS"; id: number }
    >({
      query: (body) => ({
        url: `${BASE}/credential-remove`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["credentials"],
    }),
    checkCredential: builder.mutation<
      unknown,
      { type: "SSH" | "HTTPS"; id: number }
    >({
      query: (body) => ({
        url: `${BASE}/credential-check`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["credentials", "projects"],
    }),
    getBranchGraph: builder.query<
      BranchGraphResp,
      { project?: string; config?: boolean; limit?: number }
    >({
      query: ({ project, config, limit }) =>
        `${BASE}/branch-graph?${
          config
            ? "repo=config"
            : `project=${encodeURIComponent(project || "")}`
        }&limit=${limit || 200}`,
      providesTags: ["branches"],
    }),

    checkoutBranch: builder.mutation<
      BranchCheckoutResp,
      { project?: string; config?: boolean; branch: string }
    >({
      query: ({ project, config, branch }) => ({
        url: `${BASE}/branch-checkout`,
        method: "POST",
        body: config ? { repo: "config", branch } : { project, branch },
      }),
      invalidatesTags: ["branches", "projects", "history", "status", "events"],
    }),
    getProjects: builder.query<ProjectsResp, void>({
      query: () => `${BASE}/projects`,
      providesTags: ["projects"],
    }),
    initProject: builder.mutation<
      unknown,
      {
        project: string;
        url?: string;
        sshKeyId?: number;
        httpsCredentialId?: number;
        branch?: string;
        delivery?: string;
        intervalSeconds?: number;
        ignitionUser?: string;
      } & SaveTo
    >({
      query: ({ saveTo, ...body }) => ({
        url: withSaveTo(`${BASE}/project-init`, saveTo),
        method: "POST",
        body,
      }),
      invalidatesTags: ["definition", "projects"],
    }),
    setProjectRemote: builder.mutation<
      unknown,
      { project: string; name?: string; url: string }
    >({
      query: (body) => ({
        url: `${BASE}/project-remote`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["projects"],
    }),
    snapshotProjectImages: builder.mutation<unknown, { project: string }>({
      query: (body) => ({
        url: `${BASE}/project-snapshot-images`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["projects"],
    }),
    setProjectImages: builder.mutation<
      unknown,
      { project: string; imagePrefix: string }
    >({
      query: (body) => ({
        url: `${BASE}/project-images`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["projects"],
    }),
    restore: builder.mutation<unknown, { hash: string }>({
      query: (body) => ({ url: `${BASE}/restore`, method: "POST", body }),
      invalidatesTags: ["status", "history"],
    }),
    init: builder.mutation<unknown, void>({
      query: () => ({ url: `${BASE}/init`, method: "POST", body: {} }),
      invalidatesTags: ["status", "history"],
    }),
    // Fetch the remote and bring config to its HEAD (pull latest, or re-attach + recover
    // after a gateway-backup restore that dropped .git but kept the remote record).
    updateFromRemote: builder.mutation<{ hash: string }, void>({
      query: () => ({
        url: `${BASE}/update-from-remote`,
        method: "POST",
        body: {},
      }),
      invalidatesTags: ["status", "history", "remote"],
    }),
    saveConfigDelivery: builder.mutation<
      unknown,
      { mode: ConfigDeliveryMode; branch: string; intervalSeconds: number }
    >({
      query: (body) => ({
        url: `${BASE}/config-delivery`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["remote"],
    }),
    // Switch the repository layout; refused (nothing changed) while anything is uncommitted.
    saveLayout: builder.mutation<
      {
        layout: Layout;
        projects: number;
        restored: number;
        archived: number;
        warning?: string;
      },
      { layout: Layout; restoreHistory: boolean; archiveLeftovers?: boolean }
    >({
      query: (body) => ({ url: `${BASE}/layout`, method: "POST", body }),
      invalidatesTags: [
        "status",
        "history",
        "remote",
        "projects",
        "tree",
        "events",
      ],
    }),
    configSyncNow: builder.mutation<SyncNowResult, void>({
      query: () => ({
        url: `${BASE}/config-sync-now`,
        method: "POST",
        body: {},
      }),
      invalidatesTags: ["status", "history", "remote", "events"],
    }),
    getTree: builder.query<TreeResp, string>({
      query: (path) => `${BASE}/tree?path=${encodeURIComponent(path || "")}`,
      providesTags: ["tree"],
    }),
    searchTree: builder.query<TreeSearchResp, string>({
      query: (q) => `${BASE}/tree-search?q=${encodeURIComponent(q)}`,
      providesTags: ["tree"],
    }),
    getIgnore: builder.query<IgnoreResp, void>({
      query: () => `${BASE}/ignore`,
      providesTags: ["ignore"],
    }),
    saveIgnore: builder.mutation<{ untracked: number }, IgnoreEditReq>({
      query: (body) => ({ url: `${BASE}/ignore`, method: "POST", body }),
      // Editing .gitignore changes what is tracked, so the status and the tree both move;
      // the gateway auto-commits the edit, so the history does too.
      invalidatesTags: ["tree", "ignore", "status", "history"],
    }),
    deinit: builder.mutation<unknown, { removeRemote: boolean }>({
      query: (body) => ({ url: `${BASE}/deinit`, method: "POST", body }),
      invalidatesTags: ["status", "history", "remote"],
    }),
    getEvents: builder.query<EventsResp, void>({
      query: () => `${BASE}/events`,
      providesTags: ["events"],
    }),
    clearEvents: builder.mutation<unknown, void>({
      query: () => ({ url: `${BASE}/events-clear`, method: "POST", body: {} }),
      invalidatesTags: ["events"],
    }),
    saveDelivery: builder.mutation<unknown, DeliveryReq & SaveTo>({
      query: ({ saveTo, ...body }) => ({
        url: withSaveTo(`${BASE}/delivery`, saveTo),
        method: "POST",
        body,
      }),
      invalidatesTags: ["definition", "projects"],
    }),
    syncNow: builder.mutation<SyncNowResult, { project: string }>({
      query: (body) => ({ url: `${BASE}/sync-now`, method: "POST", body }),
      invalidatesTags: ["events", "projects"],
    }),
    getRunner: builder.query<RunnerConfig, void>({
      query: () => `${BASE}/runner`,
      providesTags: ["runner"],
    }),
    // The response carries a new token exactly once, when generateToken is set; nothing reads it
    // back — a lost token is replaced, not recovered.
    saveRunner: builder.mutation<
      { hasToken: boolean; token?: string },
      { enabled?: boolean; generateToken?: boolean } & SaveTo
    >({
      query: ({ saveTo, ...body }) => ({
        url: withSaveTo(`${BASE}/runner`, saveTo),
        method: "POST",
        body,
      }),
      // "projects" too: a runner delivery shows as inactive while the runner is off.
      invalidatesTags: ["definition", "runner", "projects"],
    }),
    peekProject: builder.mutation<
      { branches: string[]; defaultBranch: string; suggestedName: string },
      {
        url: string;
        branch?: string;
        sshKeyId?: number;
        httpsCredentialId?: number;
      }
    >({
      query: (body) => ({
        url: `${BASE}/project-add-peek`,
        method: "POST",
        body,
      }),
    }),
    addProject: builder.mutation<
      { ok: boolean; project: string; branch: string },
      {
        name: string;
        url: string;
        branch?: string;
        sshKeyId?: number;
        httpsCredentialId?: number;
        delivery?: string;
        intervalSeconds?: number;
      }
    >({
      query: (body) => ({ url: `${BASE}/project-add`, method: "POST", body }),
      invalidatesTags: ["projects", "events"],
    }),
    getAudit: builder.query<AuditSettings, void>({
      query: () => `${BASE}/audit`,
      providesTags: ["audit"],
    }),
    saveAudit: builder.mutation<unknown, { enabled: boolean; profile: string }>(
      {
        query: (body) => ({ url: `${BASE}/audit`, method: "POST", body }),
        invalidatesTags: ["audit"],
      }
    ),
    savePolicy: builder.mutation<unknown, PolicyFields & SaveTo>({
      query: ({ saveTo, ...body }) => ({
        url: withSaveTo(`${BASE}/policy`, saveTo),
        method: "POST",
        body,
      }),
      invalidatesTags: ["definition", "status", "history"],
    }),
    // Deletes one mode's override of a record, so core's value applies there again.
    modeReset: builder.mutation<unknown, ModeResetReq>({
      query: (body) => ({ url: `${BASE}/mode-reset`, method: "POST", body }),
      invalidatesTags: [
        "status",
        "remote",
        "credentials",
        "projects",
        "runner",
        "history",
        "definition",
      ],
    }),
    renameMaster: builder.mutation<
      { ok: boolean; branch: string },
      { project?: string; repo?: "config" }
    >({
      query: (body) => ({
        url: `${BASE}/branch-rename-master`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["remote", "projects", "branches", "status", "events"],
    }),
    modeOverride: builder.mutation<unknown, ModeOverrideReq>({
      query: (body) => ({ url: `${BASE}/mode-override`, method: "POST", body }),
      invalidatesTags: [
        "status",
        "remote",
        "credentials",
        "projects",
        "runner",
        "history",
        "definition",
      ],
    }),
    getDefinition: builder.query<
      DefinitionResp,
      RecordRef & { collection: string }
    >({
      query: (q) =>
        `${BASE}/definition?` +
        Object.entries(q)
          .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
          .join("&"),
      providesTags: ["definition"],
    }),
    setProjectCredential: builder.mutation<
      unknown,
      {
        project: string;
        remoteName?: string;
        sshKeyId?: number;
        httpsCredentialId?: number;
      }
    >({
      query: (body) => ({
        url: `${BASE}/project-credential`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["projects"],
    }),
  }),
  overrideExisting: false,
});

export const {
  useGetStatusQuery,
  useGetHistoryQuery,
  useGetCommitFilesQuery,
  useLazyGetFileDiffQuery,
  useGetRemoteQuery,
  useGetCredentialsQuery,
  useGetSecretProvidersQuery,
  useGetSecretRefMutation,
  useSaveRemoteMutation,
  useRemoveRemoteMutation,
  useTestRemoteMutation,
  usePushMutation,
  useAddCredentialMutation,
  useRemoveCredentialMutation,
  useCheckCredentialMutation,
  useGetProjectsQuery,
  useGetBranchGraphQuery,
  useCheckoutBranchMutation,
  useInitProjectMutation,
  useSetProjectRemoteMutation,
  useSetProjectImagesMutation,
  useSnapshotProjectImagesMutation,
  useRestoreMutation,
  useInitMutation,
  useDeinitMutation,
  useUpdateFromRemoteMutation,
  useSaveConfigDeliveryMutation,
  useAbortMergeMutation,
  useConfigSyncNowMutation,
  useSaveLayoutMutation,
  useGetTreeQuery,
  useSearchTreeQuery,
  useGetIgnoreQuery,
  useSaveIgnoreMutation,
  useGetEventsQuery,
  useClearEventsMutation,
  useSaveDeliveryMutation,
  useSyncNowMutation,
  useGetRunnerQuery,
  useSaveRunnerMutation,
  useSetProjectCredentialMutation,
  useSavePolicyMutation,
  useModeResetMutation,
  useModeOverrideMutation,
  useRenameMasterMutation,
  useGetDefinitionQuery,
  useGetAuditQuery,
  useSaveAuditMutation,
  usePeekProjectMutation,
  useAddProjectMutation,
  useFetchConfigMutation,
  usePullConfigMutation,
  useJoinConfigMutation,
} = gitConfigApi;
