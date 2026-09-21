import React from "react";
import { Button, Loading, TextArea, useToastNotifications } from "../../webui";
import { SelectInput, TextInput } from "./fields";
import {
  TreeEntry,
  useGetTreeQuery,
  useGetIgnoreQuery,
  useSaveIgnoreMutation,
  useSearchTreeQuery,
} from "./GitConfig.service";
import { errorToast } from "./errors";
import { selectValue } from "./selectValue";

// A tick means "versioned". Pending edits are held here and sent as ONE request on Save, so a
// session of ticking produces a single .gitignore write and a single auto-commit rather than one
// per checkbox.
type Pending = Map<string, boolean>; // path -> versioned?

// Which folders are open, the filter, and the pending ticks — shared through context rather than
// threaded down every level, because the tree is recursive and Expand/Collapse reach all of it.
interface TreeState {
  pending: Pending;
  onToggle: (entry: TreeEntry, versioned: boolean) => void;
  open: Set<string>;
  setFolder: (path: string, open: boolean) => void;
  openMany: (paths: string[]) => void;
  showExcluded: boolean;
  // Bumped by Expand. Each level opens its own folders once per bump, and the newly mounted
  // children do the same, so one click cascades down to EXPAND_DEPTH.
  expandGen: number;
}

const Ctx = React.createContext<TreeState>(null as unknown as TreeState);

// How deep Expand goes. Every folder it opens is a request, and a data directory is deep — three
// levels is enough to see the shape of config/ without fetching the whole gateway.
const EXPAND_DEPTH = 3;

// Below this a search matches most of the directory and is slower than scrolling.
const MIN_QUERY = 2;

const Chevron = ({ open }: { open: boolean }) => (
  <svg
    viewBox="0 -960 960 960"
    width="16"
    height="16"
    fill="currentColor"
    style={{
      transform: open ? "rotate(90deg)" : "none",
      transition: "transform 120ms",
      display: "block",
    }}
  >
    <path d="M400-280v-400l200 200-200 200Z" />
  </svg>
);

const FolderIcon = () => (
  <svg viewBox="0 -960 960 960" width="16" height="16" fill="currentColor">
    <path d="M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h240l80 80h320q33 0 56.5 23.5T880-640v400q0 33-23.5 56.5T800-160H160Z" />
  </svg>
);

const FileIcon = () => (
  <svg viewBox="0 -960 960 960" width="16" height="16" fill="currentColor">
    <path d="M320-240h320v-80H320v80Zm0-160h320v-80H320v80ZM240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h320l240 240v480q0 33-23.5 56.5T720-80H240Zm280-520v-200H240v640h480v-440H520Z" />
  </svg>
);

// Rooted at the data directory: .gitignore alone decides what is versioned, so the tree is the
// gateway's real shape and every row can be ticked.
const TREE_ROOT = "";

interface RowProps {
  entry: TreeEntry;
  depth: number;
  // Search results are a flat list: the row shows the full path and never expands.
  flat?: boolean;
}

/** One row plus, when expanded, its children. */
const Row = ({ entry, depth, flat }: RowProps) => {
  const { pending, onToggle, open, setFolder } = React.useContext(Ctx);
  const isOpen = !flat && open.has(entry.path);
  const box = React.useRef<HTMLInputElement>(null);

  const pendingState = pending.get(entry.path);
  // A folder can be included itself while every file under it is excluded — `**/db/*` excludes the
  // contents, not the directory. Showing that folder fully ticked would be a lie, so the roll-up
  // decides the folder's tick, not its own flag.
  const selfVersioned =
    !entry.excluded && !(entry.directory && entry.childState === "EXCLUDED");
  const versioned = pendingState !== undefined ? pendingState : selfVersioned;
  // A folder whose children disagree shows a partial tick, the way a file picker does — but only
  // while the user has not overridden it.
  const mixed =
    pendingState === undefined &&
    entry.directory &&
    entry.childState === "MIXED";
  // Git will not re-include anything below an excluded directory, so offering the tick would be
  // offering an edit that silently does nothing.
  const locked = entry.excluded && !entry.reincludable;

  React.useEffect(() => {
    if (box.current) {
      box.current.indeterminate = mixed;
    }
  }, [mixed]);

  // Every unticked row says why. Showing the rule only when it was inherited left a row excluded
  // by its own line looking arbitrary — half the tree with no reason given at all.
  const reason = locked
    ? "excluded with its parent"
    : entry.excluded && !entry.ownRule && entry.rule
    ? `excluded by ${entry.rule}`
    : entry.excluded
    ? "excluded here"
    : entry.directory && entry.childState === "EXCLUDED"
    ? "everything inside is excluded"
    : null;

  const hint = locked
    ? `Excluded with its parent folder (${entry.rule || "inherited rule"})`
    : entry.excluded && !entry.ownRule
    ? `Excluded by ${entry.rule}`
    : entry.excluded
    ? "Excluded"
    : entry.directory && entry.childState === "EXCLUDED"
    ? "Everything inside is excluded"
    : entry.directory && entry.childState === "UNKNOWN"
    ? "Too large to summarise — open it to see"
    : entry.tracked
    ? "Versioned"
    : "Not yet versioned";

  const toggleOpen = () => entry.directory && setFolder(entry.path, !isOpen);

  return (
    <>
      <div
        className={`gitcfg-tree-row${locked ? " is-locked" : ""}`}
        style={{ paddingLeft: `${depth * 1.25 + 0.5}rem` }}
      >
        <span className="gitcfg-tree-twisty" onClick={toggleOpen}>
          {entry.directory && !flat ? <Chevron open={isOpen} /> : null}
        </span>
        <input
          type="checkbox"
          ref={box}
          checked={versioned}
          disabled={locked}
          title={hint}
          onChange={(e) => onToggle(entry, e.target.checked)}
        />
        <span className="gitcfg-tree-icon">
          {entry.directory ? <FolderIcon /> : <FileIcon />}
        </span>
        <span
          className={`gitcfg-tree-name${versioned ? "" : " is-muted"}`}
          onClick={toggleOpen}
        >
          {flat ? entry.path : entry.name}
        </span>
        {reason ? (
          <span className="gitcfg-tree-rule" title={hint}>
            {reason}
          </span>
        ) : null}
        {selfVersioned && !entry.tracked ? (
          <span className="gitcfg-tree-rule">not yet committed</span>
        ) : null}
      </div>
      {isOpen ? <Children path={entry.path} depth={depth + 1} /> : null}
    </>
  );
};

const Note = ({
  depth,
  error,
  children,
}: {
  depth: number;
  error?: boolean;
  children: React.ReactNode;
}) => (
  <div
    className={`gitcfg-tree-note${error ? " is-error" : ""}`}
    style={{ paddingLeft: `${depth * 1.25 + 2.25}rem` }}
  >
    {children}
  </div>
);

/**
 * One directory level. Each expanded folder runs its own query, so the tree loads a level at a
 * time — a data directory carries history, logs and caches, and the biggest directories in it are
 * exactly the excluded ones.
 */
const Children = ({ path, depth }: { path: string; depth: number }) => {
  const { showExcluded, expandGen, openMany } = React.useContext(Ctx);
  const { data, isFetching, error } = useGetTreeQuery(path);

  // Expand opens this level's folders, and each one that mounts repeats it — a cascade that stops
  // at EXPAND_DEPTH. Excluded folders are skipped: logs/ and db/ are the large ones, and nothing
  // in them can be re-included anyway.
  React.useEffect(() => {
    if (expandGen === 0 || !data || depth >= EXPAND_DEPTH) return;
    openMany(
      data.entries.filter((e) => e.directory && !e.excluded).map((e) => e.path)
    );
  }, [expandGen, data, depth, openMany]);

  if (isFetching) return <Note depth={depth}>Loading…</Note>;
  if (error)
    return (
      <Note depth={depth} error>
        Could not read this folder
      </Note>
    );

  const all = data ? data.entries : [];
  if (!all.length) return <Note depth={depth}>Empty folder</Note>;
  const entries = showExcluded ? all : all.filter((e) => !e.excluded);
  if (!entries.length)
    return <Note depth={depth}>Nothing versioned in this folder</Note>;
  return (
    <>
      {entries.map((e) => (
        <Row key={e.path} entry={e} depth={depth} />
      ))}
    </>
  );
};

/** Search results: a flat list of full paths, ticked the same way as the tree. */
const SearchResults = ({ query }: { query: string }) => {
  const { showExcluded } = React.useContext(Ctx);
  const { data, isFetching, error } = useSearchTreeQuery(query);
  if (isFetching) return <Note depth={0}>Searching…</Note>;
  if (error)
    return (
      <Note depth={0} error>
        Could not search
      </Note>
    );
  const all = data ? data.entries : [];
  const entries = showExcluded ? all : all.filter((e) => !e.excluded);
  if (!entries.length) return <Note depth={0}>Nothing matches “{query}”</Note>;
  return (
    <>
      {entries.map((e) => (
        <Row key={e.path} entry={e} depth={0} flat />
      ))}
      {data && data.truncated ? (
        <Note depth={0}>
          Showing the first {all.length} matches — narrow the search to see the
          rest.
        </Note>
      ) : null}
    </>
  );
};

const ExcludedFiles = () => {
  const [pending, setPending] = React.useState<Pending>(new Map());
  const [source, setSource] = React.useState(false);
  const [draft, setDraft] = React.useState<string | null>(null);
  // config/ is opened for you: collapsed, the page hides the folder it exists for.
  const [open, setOpen] = React.useState<Set<string>>(new Set(["config"]));
  const [showExcluded, setShowExcluded] = React.useState(true);
  const [expandGen, setExpandGen] = React.useState(0);
  const [typed, setTyped] = React.useState("");
  const [query, setQuery] = React.useState("");
  const { data: ignore } = useGetIgnoreQuery();
  const { data: root } = useGetTreeQuery(TREE_ROOT);
  const [save, { isLoading: saving }] = useSaveIgnoreMutation();
  const toasts = useToastNotifications();

  // Debounced: every keystroke would otherwise walk the data directory.
  React.useEffect(() => {
    const t = setTimeout(() => setQuery(typed.trim()), 300);
    return () => clearTimeout(t);
  }, [typed]);

  const setFolder = React.useCallback((path: string, isOpen: boolean) => {
    setOpen((prev) => {
      const next = new Set(prev);
      if (isOpen) next.add(path);
      else next.delete(path);
      return next;
    });
  }, []);

  const openMany = React.useCallback((paths: string[]) => {
    setOpen((prev) => {
      // Only a real change may set state here: this runs from an effect, and returning a new Set
      // every time would re-render for ever.
      if (paths.every((p) => prev.has(p))) return prev;
      const next = new Set(prev);
      paths.forEach((p) => next.add(p));
      return next;
    });
  }, []);

  const onToggle = React.useCallback((entry: TreeEntry, versioned: boolean) => {
    setPending((prev) => {
      const next = new Map(prev);
      // Ticking a row back to the state git is already in is not an edit — drop it, so Save
      // never sends a no-op and the button honestly reflects whether anything changed.
      const current =
        !entry.excluded &&
        !(entry.directory && entry.childState === "EXCLUDED");
      if (versioned === current) {
        next.delete(entry.path);
      } else {
        next.set(entry.path, versioned);
      }
      return next;
    });
  }, []);

  const exclude: string[] = [];
  const include: string[] = [];
  pending.forEach((versioned, path) =>
    (versioned ? include : exclude).push(path)
  );
  const dirty = source
    ? draft !== null && draft !== (ignore ? ignore.text : "")
    : pending.size > 0;

  const commit = () => {
    const body = source ? { text: draft || "" } : { exclude, include };
    save(body)
      .unwrap()
      .then((res) => {
        setPending(new Map());
        setDraft(null);
        const n = res && res.untracked ? res.untracked : 0;
        toasts.notifySuccess(
          n > 0
            ? `Exclusions saved. ${n} file${
                n === 1 ? "" : "s"
              } removed from version control.`
            : "Exclusions saved."
        );
      })
      .catch(errorToast(toasts, "Could not save exclusions"));
  };

  const folders = (root ? root.entries : []).filter((e) => e.directory);
  const searching = query.length >= MIN_QUERY;

  const state: TreeState = {
    pending,
    onToggle,
    open,
    setFolder,
    openMany,
    showExcluded,
    expandGen,
  };

  return (
    <div>
      <div className="gitcfg-page-head">
        <div>
          <h3>Git Ignore</h3>
          <p>
            Everything in the gateway data directory. Ticking a path versions it
            in the config repository; unticking it adds a line to{" "}
            <code>.gitignore</code>.
          </p>
          <p>
            Runtime state — databases, logs, caches and the per-project folders
            — is excluded by default, and project resources are versioned by
            their own repositories.
          </p>
          {/* A term/description grid, not a run-on line: four legend entries flowed inline read as
              one sentence with stray bold in it. */}
          <ul className="gitcfg-legend">
            <li>
              <span className="gitcfg-legend-term">Ticked</span>
              <span>Versioned.</span>
            </li>
            <li>
              <span className="gitcfg-legend-term is-muted">
                Unticked and grey
              </span>
              <span>
                Not versioned — the reason is at the right of the row.
              </span>
            </li>
            <li>
              <span className="gitcfg-legend-term">Partly ticked</span>
              <span>A folder with some of what is inside it excluded.</span>
            </li>
            <li>
              <span className="gitcfg-legend-term">not yet committed</span>
              <span>Versioned, but not in a commit yet.</span>
            </li>
          </ul>
        </div>
        <div className="gitcfg-actions">
          <Button
            colorClass="secondary"
            onClick={() => {
              setSource(!source);
              setDraft(null);
              setPending(new Map());
            }}
          >
            {source ? "Tree" : "Edit .gitignore"}
          </Button>
          <Button
            colorClass="primary"
            disabled={!dirty || saving}
            onClick={commit}
          >
            {saving ? "Saving…" : "Save"}
          </Button>
        </div>
      </div>

      {source ? (
        ignore ? (
          <TextArea
            className="gitcfg-ignore-source"
            rows={24}
            value={draft !== null ? draft : ignore.text}
            onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) =>
              setDraft(e.target.value)
            }
          />
        ) : (
          <Loading isLoading={true} />
        )
      ) : (
        <Ctx.Provider value={state}>
          <div className="gitcfg-tree-bar">
            <TextInput
              label="Search"
              placeholder="Name of a file or folder"
              value={typed}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                setTyped(e.target.value)
              }
            />
            <SelectInput
              label="Jump to"
              value=""
              values={folders.map((f) => ({ label: f.name, value: f.path }))}
              onChange={(e: unknown) => {
                const path = selectValue(e);
                if (!path) return;
                setTyped("");
                setQuery("");
                setOpen(new Set([path]));
              }}
            />
            <label className="gitcfg-check">
              <input
                type="checkbox"
                checked={showExcluded}
                onChange={(e) => setShowExcluded(e.target.checked)}
              />
              <span>Show excluded</span>
            </label>
            <div className="gitcfg-actions">
              <Button
                colorClass="secondary"
                disabled={searching}
                title={`Opens the versioned folders, ${EXPAND_DEPTH} levels deep`}
                onClick={() => setExpandGen((g) => g + 1)}
              >
                Expand
              </Button>
              <Button
                colorClass="secondary"
                disabled={searching}
                onClick={() => setOpen(new Set())}
              >
                Collapse
              </Button>
            </div>
          </div>
          <div className="gitcfg-tree">
            {searching ? (
              <SearchResults query={query} />
            ) : (
              <Children path={TREE_ROOT} depth={0} />
            )}
          </div>
        </Ctx.Provider>
      )}
    </div>
  );
};

export default ExcludedFiles;
