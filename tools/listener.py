#!/usr/bin/env python3
"""Minimal LAN listener for the Posture app.

POST /event          JSON events -> human-readable line on stdout + raw JSON appended to <out>/events.jsonl
POST /trace/<name>   gzipped trace upload        -> saved to <out>/traces/<name>
"""
import argparse, json, os, re, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def hm(ms):
    """Epoch ms -> local wall-clock time."""
    return time.strftime("%H:%M:%S", time.localtime(ms / 1000))


def mins(ms):
    return f"{ms / 60000:.1f} min"


def describe(ev):
    t = ev.get("type")
    if t == "state":
        what = f"{ev['from'].lower()} -> {ev['to'].lower()} (since {hm(ev['since'])})"
    elif t == "pocket":
        what = f"phone {'back in' if ev['in'] else 'out of'} pocket, state {ev['state'].lower()}"
    elif t == "too_long":
        what = f"TOO LONG {ev['state'].lower()}: load {mins(ev['durationMs'])}"
    elif t == "load_cleared":
        what = f"{ev['posture'].lower()} load back to zero"
    elif t == "phone_use":
        what = f"PHONE USE {mins(ev['durationMs'])}"
    elif t == "correction":
        what = (f"correction {ev['from'].lower()} -> {ev['to'].lower()} "
                f"{hm(ev['start'])}-{hm(ev['end'])} ({mins(ev['end'] - ev['start'])})")
    elif t == "heartbeat":
        state = ev["state"].lower() if ev.get("inPocket", True) else "out of pocket"
        what = f"heartbeat v{ev.get('version', '?')}: {state} for {mins(ev['t'] - ev['since'])}"
    else:
        what = json.dumps(ev)
    if "sitLoadMs" in ev:
        def load(k, lim):
            l = ev.get(lim) or 0
            return f"{ev[k] / 60000:.0f}" + (f"/{l}" if l else "")
        what += f"  [sit {load('sitLoadMs', 'sitLimitMin')} · stand {load('standLoadMs', 'standLimitMin')} min]"
    return f"{hm(ev['t'])} {ev.get('device', '')}: {what}"


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
                try:
                    line = describe(ev)
                except Exception as e:  # never drop an event over formatting
                    line = f"{json.dumps(ev)} ({e})"
                print(line, flush=True)
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
