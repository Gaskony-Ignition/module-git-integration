#!/usr/bin/env bash
# Regression suite for Git Integration on a throwaway gateway. See docs/TESTING.md.
#   test/regression/run.sh <rig-name> [--only <phase>[,<phase>]] [--keep]
#   test/regression/run.sh --local [PORT]  [--only ...] [--keep]
# A rig name takes rig-<name> if it exists, else schedules it. --local starts a stock
# inductiveautomation/ignition container gitreg-local on this machine (port 8097 by default).
# Either way it installs build/GitIntegration-<version>.modl (or $MODL) with the Example_A /
# Example_B fixtures, stands up the TLS git servers on the docker bridge of the gateway's host,
# adds their CA to the gateway's Java truststore, then runs suite.js. Prints a PASS/FAIL table;
# exits 1 on any FAIL. The gateway and the servers are always removed (--keep leaves both up,
# for a re-run with --only).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
RIG=${RIG_TOOL:-$HOME/Ignition-Work/tools/rig}
IMAGE=${IMAGE:-inductiveautomation/ignition:8.3.10}
LOCAL=; LPORT=8097; NAME=; ONLY=; KEEP=
while [ $# -gt 0 ]; do
  case $1 in
    --local) LOCAL=1; NAME=local; if [[ ${2:-} =~ ^[0-9]+$ ]]; then LPORT=$2; shift; fi; shift ;;
    --only) ONLY=$2; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) NAME=$1; shift ;;
  esac
done
[ -n "$NAME" ] || { echo "usage: run.sh <rig-name> | --local [PORT]  [--only phases] [--keep]" >&2; exit 2; }
VER=$(sed -n 's/^version=//p' "$REPO/version.properties")
MODL=${MODL:-$REPO/build/GitIntegration-$VER.modl}
[ -f "$MODL" ] || { echo "no $MODL — build first" >&2; exit 2; }

ST=$HOME/.cache/rig/gitreg-$NAME; mkdir -p "$ST"; chmod 700 "$ST"
if [ -n "$LOCAL" ]; then
  CONTAINER=gitreg-local
  hsh() { bash -s; }                       # commands for the gateway's host
  LANIP=$(hostname -I | awk '{print $1}'); GW_URL=http://localhost:$LPORT; PORT=9480
else
  CONTAINER=rig-$NAME
  RIGNAME=
  hsh() { "$RIG" sh --rig "$RIGNAME" bash -s; }
fi

cleanup() {
  local rc=$?
  if [ -z "$KEEP" ]; then
    echo "pkill -f 'gitserver[.]py $NAME-'; GNUPGHOME=/tmp/gitreg-$NAME/gnupg gpgconf --kill gpg-agent 2>/dev/null; rm -rf /tmp/gitreg-$NAME; true" | hsh >/dev/null 2>&1 || true
    if [ -n "$LOCAL" ]; then docker rm -f -v "$CONTAINER" >/dev/null 2>&1 || true; else "$RIG" down "$NAME" || true; fi
    rm -rf "$ST"
  else
    echo "kept $CONTAINER and its git servers (--keep); state in $ST"
  fi
  exit $rc
}

wait_running() {  # a pending module parks the gateway in commissioning while StatusPing says RUNNING
  local end=$(( $(date +%s) + 300 ))
  until curl -s -m3 "$GW_URL/system/gwinfo" | grep -q 'ContextStatus=RUNNING'; do
    [ "$(date +%s)" -lt "$end" ] || { echo "$CONTAINER not running after 300s" >&2; exit 6; }
    sleep 5
  done
}

# Same as `rig modl`: accepting the certificate and licence is writing both into modules.json.
install_local() {
  local d=/usr/local/bin/ignition/data entries
  entries=$(python3 -I - "$MODL" <<'PY'
import sys, zipfile, zlib, hashlib, base64, re, json, os
m = sys.argv[1]; z = zipfile.ZipFile(m)
xml = z.read("module.xml").decode()
mid = re.search(r"<id>([^<]+)", xml).group(1)
pem = z.read("certificates.p7b").decode()
der = base64.b64decode("".join(pem.split("-----")[2].split()))
e = {"filename": "/usr/local/bin/ignition/data/var/ignition/modl/" + os.path.basename(m),
     "onStartup": "enabled", "certFingerprint": hashlib.sha1(der).hexdigest()}
lic = re.search(r"<license>([^<]+)", xml)
if lic: e["licenseAgreementHash"] = zlib.crc32(z.read(lic.group(1)))
print(json.dumps({mid: e}))
PY
)
  docker exec "$CONTAINER" mkdir -p $d/var/ignition/modl $d/projects
  docker cp "$MODL" "$CONTAINER:$d/var/ignition/modl/"
  for p in Example_A Example_B; do docker cp "$HERE/fixtures/$p" "$CONTAINER:$d/projects/"; done
  docker cp "$CONTAINER:$d/modules.json" "$ST/modules.json"
  python3 -c 'import json,sys; p=sys.argv[1]; d=json.load(open(p)); d.update(json.loads(sys.argv[2])); json.dump(d, open(p,"w"), indent=2)' "$ST/modules.json" "$entries"
  docker cp "$ST/modules.json" "$CONTAINER:$d/modules.json"
  local u; u=$(docker exec "$CONTAINER" stat -c %u:%g $d)
  docker exec -u 0 "$CONTAINER" chown -R "$u" $d/modules.json $d/var/ignition/modl $d/projects
  docker restart "$CONTAINER" >/dev/null
  wait_running
}

fresh=
if [ -n "$LOCAL" ]; then
  trap cleanup EXIT INT TERM
  if ! docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    umask 077; openssl rand -hex 12 > "$ST/admin"; umask 022
    docker run -d --name "$CONTAINER" -p "$LPORT:8088" --label com.gaskony.idle-stop=never --restart no \
      -e ACCEPT_IGNITION_EULA=Y -e IGNITION_EDITION=standard -e GATEWAY_ADMIN_USERNAME=admin \
      -e GATEWAY_ADMIN_PASSWORD="$(cat "$ST/admin")" -e GATEWAY_INIT_MEMORY=512m -e GATEWAY_MAX_MEMORY=2048m \
      -e MALLOC_ARENA_MAX=2 "$IMAGE" >/dev/null
    end=$(( $(date +%s) + 300 ))
    until [ "$(curl -s -m3 "$GW_URL/StatusPing")" = '{"state":"RUNNING"}' ]; do
      [ "$(date +%s)" -lt "$end" ] || { echo "$CONTAINER not healthy" >&2; exit 6; }; sleep 5
    done
    fresh=1
  fi
  GW_PASS=$(cat "$ST/admin")
else
  if ! "$RIG" pass "$NAME" >/dev/null 2>&1; then "$RIG" up "$NAME" --wait 300; fresh=1; fi
  RIGNAME=$(cat "$HOME/.cache/rig/$NAME.rig")
  trap cleanup EXIT INT TERM
  var=RIG_HOST_$RIGNAME
  case $RIGNAME in xcp) RHOST=${!var:-nigel@10.60.9.164} ;; esac
  case $RIGNAME in esxi) RHOST=${!var:-nigel@10.60.9.197} ;; esac
  LANIP=${RHOST#*@}
  SLOT=$("$RIG" sh --rig "$RIGNAME" "docker inspect -f '{{index .Config.Labels \"rig.slot\"}}' $CONTAINER")
  GW_URL=http://$LANIP:810$SLOT
  PORT=$((9400 + SLOT * 10))
  GW_PASS=$("$RIG" pass "$NAME")
fi

if [ -n "$fresh" ] || [ ! -f "$ST/env" ]; then
  # Server passwords are made per run and live only in the 0700 state folder and the host's /tmp.
  umask 077
  printf 'W_PW=%s\nR_PW=%s\n' "$(openssl rand -hex 12)" "$(openssl rand -hex 12)" > "$ST/env"
  umask 022
  . "$ST/env"
  out=$({ printf 'NAME=%q PORT=%q LANIP=%q W_PW=%q R_PW=%q\n' "$NAME" "$PORT" "$LANIP" "$W_PW" "$R_PW"
          printf 'FIXTURES=%q\n' "$(tar -C "$HERE/fixtures" -cf - Example_A Example_B | base64 -w0)"
          printf 'GITSERVER=%q\n' "$(base64 -w0 < "$HERE/gitserver.py")"
          cat "$HERE/rig-servers.sh"; } | hsh)
  sed -n '/^CA-BEGIN$/,/^CA-END$/p' <<<"$out" | sed '1d;$d' > "$ST/ca.crt"
  [ -s "$ST/ca.crt" ] || { echo "git servers failed: $out" >&2; exit 1; }
  # The test CA goes into the gateway JVM's truststore; the module install's restart loads it.
  K=/usr/local/bin/ignition/lib/runtime/jre-nix
  { echo "cat > /tmp/gitreg-$NAME/ca.pem <<'PEM'"; cat "$ST/ca.crt"; echo PEM
    echo "docker cp /tmp/gitreg-$NAME/ca.pem $CONTAINER:/tmp/gitreg-ca.pem && docker exec -u 0 $CONTAINER $K/bin/keytool -importcert -noprompt -alias gitreg-ca -file /tmp/gitreg-ca.pem -cacerts -storepass changeit"; } | hsh >/dev/null
  # A throwaway signing key stands in for GitHub's web-flow key: the gateway trusts it instead
  # (-Dgitmodule.trustedSigningKeys, read at start-up), and the suite signs commits with it.
  { echo "set -e; G=/tmp/gitreg-$NAME/gnupg; rm -rf \$G; mkdir -m 700 -p \$G"
    echo "GNUPGHOME=\$G gpg -q --batch --pinentry-mode loopback --passphrase '' --quick-gen-key 'gitreg signer <gitreg@example.com>' rsa3072 sign 1d"
    echo "GNUPGHOME=\$G gpg -q --armor --export > /tmp/gitreg-$NAME/signing.asc"
    echo "docker cp /tmp/gitreg-$NAME/signing.asc $CONTAINER:/usr/local/bin/ignition/gitreg-signing.asc"
    echo "docker exec -u 0 $CONTAINER sh -c \"sed -i '/gitmodule.trustedSigningKeys/d' /usr/local/bin/ignition/data/ignition.conf; echo 'wrapper.java.additional.91=-Dgitmodule.trustedSigningKeys=/usr/local/bin/ignition/gitreg-signing.asc' >> /usr/local/bin/ignition/data/ignition.conf\""
  } | hsh >/dev/null
  if [ -n "$LOCAL" ]; then install_local
  else "$RIG" modl "$NAME" "$MODL" --project "$HERE/fixtures/Example_A" --project "$HERE/fixtures/Example_B"; fi
  # git inside the throwaway container, so the suite can stage states (dirty files, a lone master,
  # fetched branches) in the gateway's own repositories.
  echo "docker exec -u 0 $CONTAINER sh -c 'apt-get update -qq >/dev/null && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends git >/dev/null 2>&1'" | hsh
fi
. "$ST/env"

export GW_URL GW_PASS RIG_TOOL="$RIG" RIG_NAME="$NAME" RIG_RIG="${RIGNAME:-}" CONTAINER LOCAL \
  GIT_PORT=$PORT LANIP W_PW R_PW ONLY STATE_DIR="$ST" MODL_VERSION=$VER
node "$HERE/suite.js"
