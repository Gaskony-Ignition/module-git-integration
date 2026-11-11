# Troubleshooting

## A remote over HTTPS fails with a certificate error (4.3.0)

**Symptom.** Push, pull or Test fails with `PKIX path building failed` or
`unable to find valid certification path`, against a server that worked before
4.3.0.

**Cause.** Versions before 4.3.0 turned TLS certificate checks off in every
repository they created (`http.sslVerify=false`); 4.3.0 turns them back on at
start-up. A server whose certificate comes from a private or self-signed CA is
no longer trusted blindly.

**Fix.** Add the CA certificate to the truststore of the gateway's Java runtime
and restart the gateway, e.g.

```text
keytool -importcert -alias corp-git-ca -file corp-ca.crt \
  -keystore <ignition>/lib/runtime/jre-nix/lib/security/cacerts -storepass changeit
```

(Linux path, default store password; Windows has `jre-win`. A container needs it in the image or
a mounted truststore). Turning certificate checks off again is not an option.


## The Designer vanishes on macOS, with no error

**Symptom.** The Designer window disappears outright — no dialog, no stack
trace, nothing in `designerlauncher.log`, and nothing on the gateway. It often
follows a git pull, which is why it gets reported against this module.

**Cause.** A JDK bug, not this module.
[JDK-8341311](https://bugs.openjdk.org/browse/JDK-8341311) changed how VoiceOver
counts items in a `JPopupMenu` and introduced a regression, filed as
[JDK-8372757](https://bugs.openjdk.org/browse/JDK-8372757):
`CAccessibility.getCurrentAccessiblePopupMenu` holds the popup's Java component
through a weak reference, and the native `[MenuAccessibility
accessibilityChildren]` does not check whether that reference has gone. If the
component is garbage-collected while macOS's accessibility layer still holds the
native peer, the JVM aborts.

There is no Java exception because the JVM never gets that far — it dies in
`libawt_lwawt` on the AppKit main thread. The evidence lives only in
`~/Library/Logs/DiagnosticReports/java-*.ips`, as `EXC_BAD_ACCESS` /
`SIGABRT` with `[MenuAccessibility accessibilityChildren]` on the faulting
stack.

**Affected runtimes.** The regression shipped in **17.0.17** and the fix lands
in **17.0.21**, so 17.0.17 through 17.0.20 are exposed. The Designer's JVM comes
from the gateway, not the Mac — check
`~/.ignition/cache/resources/runtimes/`.

**It needs an accessibility client to be active.** VoiceOver, Voice Control,
Zoom or Full Keyboard Access. Without one, nothing queries the menu and the bug
cannot fire. Zoom and Full Keyboard Access are the two people leave on without
thinking of them as assistive tech.

**Why a pull looks like the trigger.** The documented reproduction is: open a
popup, force a garbage collection, open one again. A pull allocates heavily —
JGit, diffs, resource deserialisation — so it reliably supplies the GC between
two popups. Any `JComboBox` in the Designer is backed by a `JPopupMenu`, so the
Designer's own dropdowns trigger this exactly as readily as this module's
right-click menus do.

**Fixes, in order of how quickly they work:**

1. Turn off the accessibility feature — System Settings → Accessibility. The
   crash cannot occur without one.
2. Move the gateway to an Ignition build carrying JDK 17.0.21 or later. This
   cannot be patched on the Mac alone, because the launcher downloads its
   runtime from the gateway.
