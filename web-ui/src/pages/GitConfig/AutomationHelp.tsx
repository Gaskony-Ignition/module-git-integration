import React from "react";

/**
 * Which way round to point the automation, and what each way costs.
 *
 * People arrive here having heard of git-sync and wondering whether this module is an
 * alternative to it. It is not: git-sync is the pull model, and Scheduled sync / Repo updates
 * are that model. So the real choice is between two modes of one thing, and this tab exists to
 * make that choice once rather than have every step hint at it.
 */
export default function AutomationHelp() {
  return (
    <div className="gitcfg-help">
      <h4>Push or pull</h4>
      <p className="gitcfg-hint">
        &ldquo;git-sync&rdquo; is the pull model: something beside the gateway
        polls the repository on a timer and writes the files down. This module
        already does that — <strong>Scheduled sync</strong>, and the{" "}
        <strong>Repo updates</strong> delivery mode. A runner is the push model:
        GitHub hands a job to a machine you own, and that machine calls the
        gateway. So this is a choice between two modes of one product, not
        between two products, and you can use both on one gateway for different
        projects.
      </p>
      <div className="gitcfg-table-scroll">
        <table className="gitcfg-table">
          <thead>
            <tr>
              <th />
              <th>Runner (push)</th>
              <th>git-sync (pull)</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Starts the work</td>
              <td>GitHub, on a tag or a push</td>
              <td>The gateway, on a timer</td>
            </tr>
            <tr>
              <td>Inbound firewall hole</td>
              <td>none</td>
              <td>none</td>
            </tr>
            <tr>
              <td>Extra machine needed</td>
              <td>yes, a runner host</td>
              <td>no</td>
            </tr>
            <tr>
              <td>Latency</td>
              <td>seconds</td>
              <td>up to the poll interval</td>
            </tr>
            <tr>
              <td>Can run a build step</td>
              <td>yes</td>
              <td>no — it copies raw repository files</td>
            </tr>
            <tr>
              <td>Delivers a release zip</td>
              <td>yes</td>
              <td>no</td>
            </tr>
            <tr>
              <td>Pass or fail visible in GitHub</td>
              <td>yes</td>
              <td>no</td>
            </tr>
            <tr>
              <td>Suits a gated promotion</td>
              <td>yes</td>
              <td>
                yes, if the gateway tracks a branch only the promotion moves
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p className="gitcfg-hint">
        Pull wins for a gateway with no runner near it that simply tracks a
        branch. Push wins when the thing being delivered is built, or approved
        before it lands, or has to report success back to whoever released it.
      </p>
      <p className="gitcfg-hint">
        <strong>Promoting without a runner.</strong> Give the gateway a branch
        of its own — <code>deploy/site-a</code>, say — and point Scheduled sync
        at it. Anything that can move that branch then decides what this gateway
        runs: a promotion tool that writes git commits, a merge, a person.
        Nothing reaches in and no runner is needed, but the gateway needs a
        credential and a route to GitHub; what lands is the repository&apos;s
        files as committed, with no build step; the pull is refused while anyone
        has uncommitted edits on the gateway; and whoever moved the branch
        learns nothing about whether the gateway applied it — check the Event
        log.
      </p>

      <h4>Does the gateway need git credentials?</h4>
      <p className="gitcfg-hint">
        Only when the gateway itself does the git work. This is the most useful
        thing to know before setting anything up, because it decides whether the
        gateway needs to reach GitHub at all.
      </p>
      <div className="gitcfg-table-scroll">
        <table className="gitcfg-table">
          <thead>
            <tr>
              <th>What you are doing</th>
              <th>Credential on this gateway</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Runner, Release mode</td>
              <td>
                <strong>No.</strong> No repository, no remote, no credential —
                the runner does every git operation and the gateway receives an
                authenticated zip. Works on a gateway with no internet access.
              </td>
            </tr>
            <tr>
              <td>Runner, Repo updates mode</td>
              <td>
                <strong>Yes.</strong> The project must be registered on the
                Projects tab with a remote and a credential.
              </td>
            </tr>
            <tr>
              <td>Scheduled sync</td>
              <td>
                <strong>Yes</strong> — the same registration as Repo updates.
              </td>
            </tr>
            <tr>
              <td>Versioning the gateway config</td>
              <td>
                Only if you give the config repository a remote to push to.
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <h4>A gateway this module cannot be installed on</h4>
      <p className="gitcfg-hint">
        Edge, or any gateway whose module set is fixed, cannot run this module —
        so there is nothing here to receive a release. The runner still is the
        answer, and nothing about the GitHub half changes: same workflow, same
        labels, same self-hosted runner on that machine. Only the last step
        differs, because the runner writes the project in itself instead of
        handing it to a gateway that installs it.
      </p>
      <p className="gitcfg-hint">
        Four things about Edge decide the shape of that step:
      </p>
      <ul className="gitcfg-hint">
        <li>
          <strong>Edge has no project import.</strong> There is no route and no
          upload — the release is copied into{" "}
          <code>data/projects/&lt;project&gt;/</code> directly.
        </li>
        <li>
          <strong>Edge runs exactly one project</strong>, whose name comes from
          the gateway&apos;s own <code>edge-system-properties</code> (
          <code>Edge</code> by default). The folder must carry that name, not
          whatever the repository is called.
        </li>
        <li>
          <strong>Own the files.</strong> A copy lands as whoever ran it, and a
          project the gateway cannot read is skipped in silence — no error, no
          log line, the old project simply stays. Change ownership to the
          gateway&apos;s user (uid 2003 in Inductive Automation&apos;s images).
        </li>
        <li>
          <strong>Restart it.</strong> A scan is what applies a change here;
          with no module to request one, Edge picks the project up on startup.
        </li>
      </ul>
      <p className="gitcfg-hint">
        So the workflow step becomes: stop the gateway, replace the project
        folder, restore anything worth keeping across a deploy (its{" "}
        <code>.git</code> if it has one, and{" "}
        <code>ignition/global-props/data.bin</code>, which holds that
        gateway&apos;s own settings), change ownership, start it again. The
        runner needs whatever access that machine requires — a Docker volume, a
        file share, or simply being the machine.
      </p>
      <p className="gitcfg-hint">
        The cost is the restart and the access: this gateway installs a release
        without either. That is the reason to prefer the module where it can be
        installed, not a reason to avoid Edge.
      </p>

      <h4>Labels, and why a job can queue for ever</h4>
      <p className="gitcfg-hint">
        A runner is registered with labels, and a workflow&apos;s{" "}
        <code>runs-on</code> lists labels. GitHub sends the job to a runner
        carrying <em>all</em> of them. GitHub adds <code>self-hosted</code> plus
        the OS and architecture automatically; any others are yours to choose.
        If the two lists do not match, the job waits with no error and no
        timeout for a day — so if a deploy never starts, check the labels before
        anything else.
      </p>
      <p className="gitcfg-hint">
        Two gateways must not share a label: the job has to land on the machine
        that can reach the right gateway. When a second gateway and a second
        runner appear, do not edit every workflow — make the label an input of
        the workflow and pass it per environment. GitHub&apos;s own mechanism
        for this is runner groups, which private repositories cannot use below
        the Team plan.
      </p>
    </div>
  );
}
