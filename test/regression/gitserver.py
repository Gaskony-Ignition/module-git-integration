# Smart-HTTP git server over TLS with Basic auth, for the regression suite only.
# Wraps git http-backend (CGI). Every request is logged as one line:
#   <server> <user|-> <METHOD> <path> <status>
# so a test can tell which credential reached which server.
#   python3 gitserver.py NAME BIND PORT ROOT CERT KEY USERS LOG [REDIRECT_BASE]
# USERS is a file of "user:password:role" lines, role writer or reader (a reader cannot push).
# With REDIRECT_BASE, every request is answered 302 to REDIRECT_BASE + path instead.
import base64, http.server, os, ssl, subprocess, sys

NAME, BIND, PORT, ROOT, CERT, KEY, USERS, LOG = sys.argv[1:9]
REDIRECT = sys.argv[9] if len(sys.argv) > 9 else None
users = {}
for line in open(USERS):
    if line.strip():
        u, p, role = line.strip().split(":", 2)
        users[u] = (p, role)
log = open(LOG, "a", buffering=1)


class H(http.server.BaseHTTPRequestHandler):
    def who(self):
        a = self.headers.get("Authorization", "")
        if not a.startswith("Basic "):
            return None
        try:
            u, _, p = base64.b64decode(a[6:]).decode().partition(":")
        except Exception:
            return None
        return u if u in users and users[u][0] == p else "!" + u

    def answer(self, status, headers=(), body=b""):
        self.send_response(status)
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def run(self):
        user = self.who()
        path, _, qs = self.path.partition("?")
        status = self._run(user, path, qs)
        log.write(f"{NAME} {user or '-'} {self.command} {self.path} {status}\n")

    def _run(self, user, path, qs):
        if REDIRECT:
            self.answer(302, [("Location", REDIRECT + self.path)])
            return 302
        if user is None or user.startswith("!"):
            self.answer(401, [("WWW-Authenticate", 'Basic realm="git"')])
            return 401
        push = "git-receive-pack" in path or "service=git-receive-pack" in qs
        if push and users[user][1] != "writer":
            self.answer(403, body=b"read-only user\n")
            return 403
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else b""
        env = dict(os.environ, GIT_PROJECT_ROOT=ROOT, GIT_HTTP_EXPORT_ALL="1", PATH_INFO=path,
                   QUERY_STRING=qs, REQUEST_METHOD=self.command, REMOTE_USER=user, REMOTE_ADDR="x",
                   CONTENT_TYPE=self.headers.get("Content-Type", ""), CONTENT_LENGTH=str(len(body)),
                   GIT_HTTP_RECEIVE_PACK="1")
        if self.headers.get("Content-Encoding"):
            env["HTTP_CONTENT_ENCODING"] = self.headers["Content-Encoding"]
        out = subprocess.run(["/usr/lib/git-core/git-http-backend"], input=body, env=env,
                             capture_output=True).stdout
        head, _, rest = out.partition(b"\r\n\r\n")
        status, hdrs = 200, []
        for line in head.decode(errors="replace").split("\r\n"):
            if not line:
                continue
            k, _, v = line.partition(":")
            if k.lower() == "status":
                status = int(v.strip().split()[0])
            else:
                hdrs.append((k, v.strip()))
        self.answer(status, hdrs, rest)
        return status

    do_GET = run
    do_POST = run

    def log_message(self, *a):
        pass


srv = http.server.ThreadingHTTPServer((BIND, int(PORT)), H)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(CERT, KEY)
srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
srv.serve_forever()
