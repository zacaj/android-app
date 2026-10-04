#!/usr/bin/env python3
"""Minimal LAN listener for the Posture app.

POST /event          JSON state/too_long/pocket/heartbeat/correction events -> printed + appended to <out>/events.jsonl
POST /trace/<name>   gzipped trace upload        -> saved to <out>/traces/<name>
"""
import argparse, json, os, re, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--out", default="listener-out")
    args = ap.parse_args()
    os.makedirs(os.path.join(args.out, "traces"), exist_ok=True)
    events_path = os.path.join(args.out, "events.jsonl")

    class H(BaseHTTPRequestHandler):
        def do_GET(self):
            self._reply(200, b"ok\n")

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            if self.path == "/event":
                ev = json.loads(body)
                ev["received"] = time.time()
                with open(events_path, "a") as f:
                    f.write(json.dumps(ev) + "\n")
                print(time.strftime("%H:%M:%S"), json.dumps(ev), flush=True)
                # Hook your own actions in here (desk lights, home automation, ...).
                return self._reply(200, b"ok\n")
            m = re.fullmatch(r"/trace/([\w.\-]+)", self.path)
            if m:
                with open(os.path.join(args.out, "traces", m.group(1)), "wb") as f:
                    f.write(body)
                print(time.strftime("%H:%M:%S"), "trace", m.group(1), len(body), "bytes", flush=True)
                return self._reply(200, b"ok\n")
            self._reply(404, b"not found\n")

        def _reply(self, code, body):
            self.send_response(code)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    print(f"listening on :{args.port}, writing to {args.out}", file=sys.stderr, flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), H).serve_forever()


if __name__ == "__main__":
    main()
