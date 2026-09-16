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

export interface SyncSetting {
  project: string;
  enabled: boolean;
  remoteName: string;
  branch: string;
  intervalSeconds: number;
  ignitionUser: string;
}
export interface RunnerProject {
  name: string;
  hasRemote: boolean;
  // "release": a release zip replaces the project. "repo": the gateway pulls the branch.
  mode: "release" | "repo";
}
// A workflow file already in the project's repository. `callsGateway` is true when its text
// mentions a runner route, so the page can say "this repo already deploys through the module"
// instead of asking for a second workflow beside the one that does.
export interface RunnerWorkflow {
  path: string;
  callsGateway: boolean;
}
export interface RunnerWorkflows {
  // False when there is no working tree to look in — a release-mode project need not be a
  // repository here at all, and "cannot check" is a different answer from "none".
  detectable: boolean;
  list: RunnerWorkflow[];
}
export interface RunnerConfig {
  enabled: boolean;
  hasToken: boolean;
  gatewayUrl: string;
  labels: string;
  projects: RunnerProject[];
  // Empty until one is chosen; the snippets then carry placeholders.
  project: string;
  mode: "release" | "repo";
  hasRemote: boolean;
  repoUrl: string;
  // The owning organisation, for a runner that serves every repository in it.
  orgUrl: string;
  scope: "repo" | "org";
  // Epoch millis of the last authenticated runner call, 0 if none since the gateway started.
  runnerSeen: { at: number; kind: string };
  workflows: RunnerWorkflows;
  installScript: string;
  installScriptMac: string;
  // PowerShell equivalent of installScript, for a Windows runner machine.
  installScriptWindows: string;
  workflowPath: string;
  workflowYaml: string;
  testCommand: string;
  // PowerShell form: Windows PowerShell 5.1 aliases curl to Invoke-WebRequest.
  testCommandWindows: string;
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
export interface AutomationResp {
  syncs: SyncSetting[];
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
    addCredential: builder.mutation<
      { id: number; type: string },
      AddCredentialReq
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
    getAutomation: builder.query<AutomationResp, void>({
      query: () => `${BASE}/automation`,
      providesTags: ["automation"],
    }),
    clearAutomationLog: builder.mutation<unknown, void>({
      query: () => ({
        url: `${BASE}/automation-clear`,
        method: "POST",
        body: {},
      }),
      invalidatesTags: ["automation"],
    }),
    saveSync: builder.mutation<unknown, SyncSetting>({
      query: (body) => ({ url: `${BASE}/sync`, method: "POST", body }),
      invalidatesTags: ["automation", "projects"],
    }),
    syncNow: builder.mutation<{ result: string }, { project: string }>({
      query: (body) => ({ url: `${BASE}/sync-now`, method: "POST", body }),
      invalidatesTags: ["automation", "projects"],
    }),
    // `scope`, `labels` and `gatewayUrl` only change the generated commands, so the page passes
    // what has been typed and previews it before Save. The gateway reads none of them when a
    // runner calls, and this GET writes nothing.
    getRunner: builder.query<
      RunnerConfig,
      {
        project?: string;
        scope?: "repo" | "org";
        labels?: string;
        gatewayUrl?: string;
      }
    >({
      query: (args) => {
        const q = new URLSearchParams();
        Object.entries(args || {}).forEach(([k, v]) => {
          if (v) q.set(k, String(v));
        });
        const s = q.toString();
        return s ? `${BASE}/runner?${s}` : `${BASE}/runner`;
      },
      providesTags: ["runner"],
    }),
    // The response carries the new token exactly once, when generateToken is set. There is no
    // endpoint that reads it back — a lost token is replaced, not recovered.
    saveRunner: builder.mutation<
      { hasToken: boolean; token?: string },
      {
        enabled?: boolean;
        gatewayUrl?: string;
        labels?: string;
        project?: string;
        mode?: "release" | "repo";
        generateToken?: boolean;
        clearToken?: boolean;
      }
    >({
      query: (body) => ({ url: `${BASE}/runner`, method: "POST", body }),
      invalidatesTags: ["runner"],
    }),
    commitRunnerWorkflow: builder.mutation<
      {
        committed?: boolean;
        unchanged?: boolean;
        pushed?: boolean;
        pushError?: string;
      },
      { project: string; overwrite?: boolean }
    >({
      query: (body) => ({
        url: `${BASE}/runner-workflow`,
        method: "POST",
        body,
      }),
      invalidatesTags: ["runner", "history", "projects"],
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
  useGetAutomationQuery,
  useClearAutomationLogMutation,
  useSaveSyncMutation,
  useSyncNowMutation,
  useGetRunnerQuery,
  useSaveRunnerMutation,
  useCommitRunnerWorkflowMutation,
  useSetProjectCredentialMutation,
} = gitConfigApi;
