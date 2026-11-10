// Shared error surfacing for the config-versioning page.
//
// The gateway returns errors as HTTP 500 with { error: "<message>" }. We show them as native
// toast notifications (the platform's ToastContainer is mounted by the gateway app shell). Errors
// are sticky + dismissible rather than auto-closing, so longer, actionable messages — e.g. the
// non-fast-forward push explanation — stay on screen until the user reads and dismisses them.

type ToastApi = { notify: (props: any) => unknown };

// The gateway puts the raw exception after this marker, behind its plain message.
const DETAILS = "\n\nDetails: ";

/** Pull the gateway's message out of an RTK Query error object, with sensible fallbacks. */
export const errorMessage = (e: any): string => {
  const m: string =
    (e && e.data && e.data.error) ||
    (e && e.error) ||
    "The operation failed. Check the gateway logs for details.";
  const d = m.indexOf(DETAILS);
  if (d > 0) {
    // The plain message on screen; the raw exception for whoever opens the console.
    console.warn(m.slice(d + DETAILS.length));
    return m.slice(0, d);
  }
  return m;
};

/**
 * Build a `.catch(...)` handler that shows the error as a sticky toast titled `title`.
 * Usage: `mutation().unwrap().catch(errorToast(toasts, "Remote Sync failed"))`.
 */
export const errorToast =
  (toasts: ToastApi, title: string) =>
  (e: any): void => {
    toasts.notify({
      type: "error",
      title,
      message: errorMessage(e),
      autoClose: false,
      isDismissible: true,
    });
  };
