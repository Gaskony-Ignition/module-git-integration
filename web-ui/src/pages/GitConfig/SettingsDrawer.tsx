import React from "react";
import {
  Drawer,
  DrawerTemplate,
  DrawerTemplateSize,
  DrawerTemplateColorTheme,
  useToastNotifications,
} from "../../webui";
import { errorToast } from "./errors";

/**
 * The lateral panel every settings edit on this page opens in, matching ConfigDrawer's platform
 * Drawer + DrawerTemplate (SMALL, GREY) so every edit follows the same v8.3 pattern: Save and
 * Cancel in the drawer's own footer, nothing written until Save is pressed.
 *
 * Callers keep `open` and the draft data in their own state, but must NOT null the draft out on
 * close — only flip `open` to false. MUI's Drawer plays its slide-out transition itself; if the
 * caller unmounts this component (or blanks the draft) the instant Cancel is pressed, the closing
 * animation never has anything left to animate and the panel just vanishes.
 */
const SettingsDrawer = ({
  open,
  title,
  onClose,
  onSave,
  saveDisabled,
  errorTitle,
  children,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  /** Perform the save. Resolving closes the drawer; rejecting shows a sticky error toast and
   * leaves it open so the change is not lost. */
  onSave: () => Promise<unknown>;
  saveDisabled?: boolean;
  /** Toast title on failure. Defaults to "Could not save <title>". */
  errorTitle?: string;
  children: React.ReactNode;
}) => {
  const [saving, setSaving] = React.useState(false);
  const toasts = useToastNotifications();

  const handleSave = async () => {
    setSaving(true);
    try {
      await onSave();
      onClose();
    } catch (e) {
      errorToast(toasts, errorTitle || `Could not save ${title}`)(e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
      size={DrawerTemplateSize.SMALL}
    >
      <DrawerTemplate
        title={title}
        path={[title]}
        theme={DrawerTemplateColorTheme.GREY}
        primaryActionText={saving ? "Saving…" : "Save"}
        secondaryActionText="Cancel"
        primaryDisabled={saving || !!saveDisabled}
        onComplete={handleSave}
        onCancel={onClose}
        onClose={onClose}
      >
        {/* The drawer renders in a portal outside the page, so it needs `.gitcfg` itself for
            the --gitcfg-* tokens; without it every spacing value falls back to zero. */}
        <div className="gitcfg gitcfg-form">{children}</div>
      </DrawerTemplate>
    </Drawer>
  );
};

export default SettingsDrawer;
