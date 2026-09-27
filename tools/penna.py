#!/usr/bin/env python3
"""Simulatore di S Pen per i test di Tratto.

Scrive eventi grezzi nel dispositivo della penna del Tab S6 Lite (sec_e-pen,
/dev/input/event2) tramite adb, quindi le app ricevono MotionEvent veri con
TOOL_TYPE_STYLUS, pressione, inclinazione e tasto laterale.

Uso:
  penna.py tratto x1,y1 x2,y2 ...        tratto tra punti in pixel schermo
  penna.py onda X Y LARGHEZZA            onda con pressione che cresce
  penna.py doppioclic X Y                doppio clic del tasto in hover
  penna.py tap X Y
"""
import math, struct, subprocess, sys, tempfile, os

DEV = "/dev/input/event2"
SCALA = 13536 / 1200  # unita' digitizer per pixel (uguale su X e Y)
EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
ABS_X, ABS_Y, ABS_PRESSURE, ABS_DISTANCE, ABS_TILT_X, ABS_TILT_Y = 0x00, 0x01, 0x18, 0x19, 0x1a, 0x1b
BTN_TOOL_PEN, BTN_TOUCH, BTN_STYLUS = 0x140, 0x14a, 0x14b

def ev(t, c, v):
    return struct.pack("<qqHHi", 0, 0, t, c, v)

def syn():
    return ev(EV_SYN, 0, 0)

class Script:
    """Accumula frame di eventi e pause, poi li esegue sul tablet in un colpo solo."""
    def __init__(self):
        self.frames = []  # (bytes, pausa_s)
    def frame(self, data, pausa=0.008):
        self.frames.append((data + syn(), pausa))
    def run(self):
        with tempfile.TemporaryDirectory() as d:
            blob = b"".join(f for f, _ in self.frames)
            open(os.path.join(d, "ev.bin"), "wb").write(blob)
            lines, off = [], 0
            for f, p in self.frames:
                lines.append(f"dd if=/data/local/tmp/ev.bin of={DEV} bs=24 skip={off // 24} count={len(f) // 24} 2>/dev/null; sleep {p}")
                off += len(f)
            open(os.path.join(d, "ev.sh"), "w").write("\n".join(lines) + "\n")
            subprocess.run(["adb", "push", os.path.join(d, "ev.bin"), os.path.join(d, "ev.sh"), "/data/local/tmp/"], check=True, capture_output=True)
            subprocess.run(["adb", "shell", "sh /data/local/tmp/ev.sh"], check=True)

def pos(x, y):
    return ev(EV_ABS, ABS_X, int(x * SCALA)) + ev(EV_ABS, ABS_Y, int(y * SCALA))

def hover_in(s, x, y, dist=40):
    s.frame(ev(EV_KEY, BTN_TOOL_PEN, 1) + pos(x, y) + ev(EV_ABS, ABS_DISTANCE, dist), 0.03)

def hover_out(s):
    s.frame(ev(EV_KEY, BTN_TOOL_PEN, 0), 0.05)

def stroke(s, punti, tilt=(0, 0)):
    """punti: lista di (x, y, pressione 0..1)."""
    x0, y0, p0 = punti[0]
    hover_in(s, x0, y0)
    s.frame(ev(EV_ABS, ABS_DISTANCE, 0) + ev(EV_KEY, BTN_TOUCH, 1) + pos(x0, y0)
            + ev(EV_ABS, ABS_PRESSURE, int(p0 * 4095)) + ev(EV_ABS, ABS_TILT_X, tilt[0]) + ev(EV_ABS, ABS_TILT_Y, tilt[1]))
    for x, y, p in punti[1:]:
        s.frame(pos(x, y) + ev(EV_ABS, ABS_PRESSURE, max(1, int(p * 4095))))
    s.frame(ev(EV_ABS, ABS_PRESSURE, 0) + ev(EV_KEY, BTN_TOUCH, 0), 0.02)
    hover_out(s)

def interpola(a, b, n):
    return [(a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n) for i in range(n + 1)]

def main(argv):
    s = Script()
    cmd = argv[0]
    if cmd == "tratto":
        pts = [tuple(map(float, a.split(","))) for a in argv[1:]]
        seq = []
        for a, b in zip(pts, pts[1:]):
            seq += interpola(a, b, max(4, int(math.dist(a, b) / 6)))
        stroke(s, [(x, y, 0.6) for x, y in seq])
    elif cmd == "onda":
        X, Y, W = map(float, argv[1:4])
        n = int(W / 5)
        seq = [(X + W * i / n, Y + 60 * math.sin(i / n * 4 * math.pi), 0.1 + 0.9 * i / n) for i in range(n + 1)]
        stroke(s, seq, tilt=(20, -10))
    elif cmd == "doppioclic":
        X, Y = map(float, argv[1:3])
        hover_in(s, X, Y)
        for _ in range(2):
            s.frame(ev(EV_KEY, BTN_STYLUS, 1), 0.07)
            s.frame(ev(EV_KEY, BTN_STYLUS, 0), 0.12)
        hover_out(s)
    elif cmd == "tap":
        X, Y = map(float, argv[1:3])
        stroke(s, [(X, Y, 0.5), (X, Y, 0.5)])
    else:
        print(__doc__); return 1
    s.run()

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
