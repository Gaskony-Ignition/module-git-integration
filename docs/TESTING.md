# Testing

`./gradlew test` covers the pure logic (branches, layout, Git ignore, policy, URLs, paths).
Everything that needs a running gateway is in the regression suite.

## Regression suite

```bash
rm -rf build/moduleContent && ./gradlew build
test/regression/run.sh gitreg            # any free rig slot name
```

`run.sh` schedules `rig-<name>` (or uses it if it is already up), installs
`build/GitIntegration-<version>.modl` (or `$MODL`) with the neutral fixtures `Example_A` and
`Example_B`, and on the rig host starts five smart-HTTP git servers over TLS on the docker
bridge: `dev` and `prod` (users `writer` and `reader`; a reader cannot push), `untrusted`
(a self-signed certificate), `redirect` (302 to `sink` on the rig's LAN address) and `sink`.
Their test CA goes into the gateway JVM's truststore. Every request is logged with the
server and user, which is how the suite proves a credential reached only its own server.

It then runs `suite.js` in six phases — `sep1` (Separate), `comb` (Combined), `sep2`
(Separate again, history restored), `audit`, `merged` and `modes` (core, then a restart in
`Dev`, then in `Prod`) — covering config versioning, projects and branches, deliveries and the
runner, per-mode records, and the security refusals (project names, file paths, remote URLs,
credential owners and hosts, TLS, redirects, an unreadable policy). `audit` sets a local audit
profile as the gateway's own and checks one row per essential action and none for a busy sync
loop. `merged` turns on Only deliver commits merged on GitHub: `run.sh` makes a throwaway GPG
key on the gateway's host, the gateway trusts it in place of GitHub's
(`-Dgitmodule.trustedSigningKeys` in `ignition.conf`, test gateways only), and the suite signs
commits with it. It prints a PASS/FAIL
table and exits 1 on any FAIL.

The rig slot and the servers are always released. `--keep` leaves both up, and
`--only comb,sep2` re-runs some phases against that state. The admin password is read with
`rig pass` at run time; the server passwords are made per run and kept in
`~/.cache/rig/gitreg-<name>/` (0700) until the run ends.

Not scripted: the Designer (commit, push, pull, branch popup, revert author, and the audit rows
its push, pull, checkout, revert, remote and credential changes write) — smoke it by hand with the
`ignition:designer-drive` skill in both layouts.
