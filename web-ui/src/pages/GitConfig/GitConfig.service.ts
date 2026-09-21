import baseApi from "../../api/index";
import { BASE } from "../../config";

export interface ConfigChange {
  path: string;
  type: string;
}
export interface StatusResp {
  initialized: boolean;
  dirty: boolean;
  changes: ConfigChange[];
  // The installed module's version, e.g. "3.7.0". Shown under the page title.
  version?: string;
}
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
  uri?: string;
  branch?: string;
  // Secret prefill (the embedded secret itself is never returned).
  secretMode?: "inline" | "reference";
  username?: string;
  providerName?: string;
  secretName?: string;
  // Local commits not yet on the remote (0 = up to date).
  ahead?: number;
  lastPush?: { time: number; ok: boolean; error?: string };
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
}
export interface ProjectsResp {
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
    saveRemote: builder.mutation<unknown, RemoteSecretReq & { branch: string }>(
      {
        query: (body) => ({ url: `${BASE}/remote`, method: "POST", body }),
        // Saving the remote writes (and the gateway commits) the config-remote/credential
        // resources, so refresh the history table too — not just the remote indicator.
        invalidatesTags: ["remote", "history"],
      }
    ),
    removeRemote: builder.mutation<unknown, void>({
      query: () => ({ url: `${BASE}/remote-remove`, method: "POST", body: {} }),
      invalidatesTags: ["remote", "history"],
    }),
    testRemote: builder.mutation<unknown, RemoteSecretReq>({
      query: (body) => ({ url: `${BASE}/remote-test`, method: "POST", body }),
    }),
    push: builder.mutation<unknown, void>({
      query: () => ({ url: `${BASE}/push`, method: "POST", body: {} }),
      // History too: a push flips commits from local to remote.
      invalidatesTags: ["remote", "history"],
    }),
    getSecretProviders: builder.query<SecretProvidersResp, void>({
      query: () => `${BASE}/secret-providers`,
      providesTags: ["secretProviders"],
    }),
    // With an id, edits that credential in place.
    addCredential: builder.mutation<
      { id: number; type: string },
      AddCredentialReq & { id?: number }
    >({
      query: (body) => ({ url: `${BASE}/credentials`, method: "POST", body }),
      invalidatesTags: ["credentials"],
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
      }
    >({
      query: (body) => ({ url: `${BASE}/project-init`, method: "POST", body }),
      invalidatesTags: ["projects"],
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
    getTree: builder.query<TreeResp, string>({
      query: (path) => `${BASE}/tree?path=${encodeURIComponent(path || "")}`,
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
    deinit: builder.mutation<unknown, void>({
      query: () => ({ url: `${BASE}/deinit`, method: "POST", body: {} }),
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
    saveDelivery: builder.mutation<unknown, DeliveryReq>({
      query: (body) => ({ url: `${BASE}/delivery`, method: "POST", body }),
      invalidatesTags: ["projects"],
    }),
    syncNow: builder.mutation<{ result: string }, { project: string }>({
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
      { enabled?: boolean; generateToken?: boolean }
    >({
      query: (body) => ({ url: `${BASE}/runner`, method: "POST", body }),
      // "projects" too: a runner delivery shows as inactive while the runner is off.
      invalidatesTags: ["runner", "projects"],
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
  useSaveRemoteMutation,
  useRemoveRemoteMutation,
  useTestRemoteMutation,
  usePushMutation,
  useAddCredentialMutation,
  useRemoveCredentialMutation,
  useCheckCredentialMutation,
  useGetProjectsQuery,
  useInitProjectMutation,
  useSetProjectRemoteMutation,
  useSetProjectImagesMutation,
  useSnapshotProjectImagesMutation,
  useRestoreMutation,
  useInitMutation,
  useDeinitMutation,
  useUpdateFromRemoteMutation,
  useGetTreeQuery,
  useGetIgnoreQuery,
  useSaveIgnoreMutation,
  useGetEventsQuery,
  useClearEventsMutation,
  useSaveDeliveryMutation,
  useSyncNowMutation,
  useGetRunnerQuery,
  useSaveRunnerMutation,
  useSetProjectCredentialMutation,
} = gitConfigApi;
