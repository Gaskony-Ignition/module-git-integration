# Runs ON the rig host (piped to `rig sh bash -s` by run.sh, with NAME, PORT, LANIP, W_PW, R_PW
# FIXTURES (base64 tar) and GITSERVER (base64 gitserver.py) set ahead of it). Stands up the suite's git servers in /tmp/gitreg-NAME:
#   PORT    dev       writer + reader, repos example-a, example-b, gateway
#   PORT+1  prod      same users, its own repos (example-a, gateway)
#   PORT+2  untrusted dev's repos behind a self-signed certificate the gateway does not trust
#   PORT+3  redirect  answers every request 302 to the sink, on the rig's LAN address
#   PORT+4  sink      dev's repos, LAN-bound, logs anything that reaches it
# Servers bind the docker bridge (172.17.0.1), except the sink. Prints the test CA between
# CA-BEGIN/CA-END for the gateway's truststore.
set -euo pipefail
D=/tmp/gitreg-$NAME
pkill -f "gitserver[.]py $NAME-" 2>/dev/null || true
rm -rf "$D"; mkdir -p "$D"/{dev,prod,work}; cd "$D"
BR=172.17.0.1

openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=gitreg test CA $NAME" \
  -keyout ca.key -out ca.crt 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=$BR" -keyout srv.key -out srv.csr 2>/dev/null
printf 'subjectAltName=IP:%s,IP:%s\n' "$BR" "$LANIP" > san.ext
openssl x509 -req -in srv.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 2 -extfile san.ext \
  -out srv.crt 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=$BR" -addext "subjectAltName=IP:$BR" \
  -keyout self.key -out self.crt 2>/dev/null
umask 077
printf 'writer:%s:writer\nreader:%s:reader\n' "$W_PW" "$R_PW" > users
umask 022

echo "$FIXTURES" | base64 -d | tar -xmf - -C work
seed() {  # seed <root> <repo> <fixture-folder|empty>
  git init -q --bare -b main "$1/$2.git"
  git -C "$1/$2.git" config http.receivepack true
  rm -rf work/seed; mkdir work/seed
  if [ -n "$3" ]; then cp -r "work/$3/." work/seed/; else echo "seed" > work/seed/README; fi
  git -C work/seed init -q -b main
  git -C work/seed -c user.name=fixture -c user.email=fixture@example.com add -A
  git -C work/seed -c user.name=fixture -c user.email=fixture@example.com commit -qm "Fixture $2"
  git -C work/seed push -q "$D/$1/$2.git" main
}
seed dev example-a Example_A
seed dev example-b Example_B
git init -q --bare -b main dev/gateway.git; git -C dev/gateway.git config http.receivepack true
seed prod example-a Example_A
git init -q --bare -b main prod/gateway.git; git -C prod/gateway.git config http.receivepack true
chmod -R a+rwX "$D/dev" "$D/prod"

S=$D/gitserver.py; echo "$GITSERVER" | base64 -d > "$S"
start() { setsid nohup python3 "$S" "$NAME-$1" "$2" "$3" "$D/$4" "$D/$5.crt" "$D/$5.key" "$D/users" "$D/requests.log" ${6:-} >"$D/$1.out" 2>&1 < /dev/null & }
start dev "$BR" "$PORT" dev srv
start prod "$BR" "$((PORT + 1))" prod srv
start untrusted "$BR" "$((PORT + 2))" dev self
start redirect "$BR" "$((PORT + 3))" dev srv "https://$LANIP:$((PORT + 4))"
start sink 0.0.0.0 "$((PORT + 4))" dev srv
# A loaded host takes a few seconds to start five Pythons; wait up to 30 s for each.
for p in 0 1 2 3 4; do
  for _ in $(seq 60); do ss -ltn | grep -q ":$((PORT + p)) " && break; sleep 0.5; done
  ss -ltn | grep -q ":$((PORT + p)) " || { echo "server on $((PORT + p)) did not start"; cat "$D"/*.out; exit 1; }
done
echo CA-BEGIN; cat ca.crt; echo CA-END
