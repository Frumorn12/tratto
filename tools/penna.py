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
  penna.py scrivi X Y ALTEZZA testo...   scrittura a mano (serve il pacchetto Hershey-Fonts)
  penna.py scorri X0 Y0 X1 Y1            trascinamento con un dito
  penna.py pizzica CX CY D0 D1           pizzico con due dita (distanza iniziale e finale)

Opzioni per tratto: --pressione P (0..1), --tasto (tasto laterale premuto: gomma temporanea)
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

def stroke(s, punti, tilt=(0, 0), tasto=False):
    """punti: lista di (x, y, pressione 0..1)."""
    x0, y0, p0 = punti[0]
    hover_in(s, x0, y0)
    if tasto:
        s.frame(ev(EV_KEY, BTN_STYLUS, 1), 0.03)
    s.frame(ev(EV_ABS, ABS_DISTANCE, 0) + ev(EV_KEY, BTN_TOUCH, 1) + pos(x0, y0)
            + ev(EV_ABS, ABS_PRESSURE, int(p0 * 4095)) + ev(EV_ABS, ABS_TILT_X, tilt[0]) + ev(EV_ABS, ABS_TILT_Y, tilt[1]))
    for x, y, p in punti[1:]:
        s.frame(pos(x, y) + ev(EV_ABS, ABS_PRESSURE, max(1, int(p * 4095))))
    s.frame(ev(EV_ABS, ABS_PRESSURE, 0) + ev(EV_KEY, BTN_TOUCH, 0), 0.02)
    if tasto:
        s.frame(ev(EV_KEY, BTN_STYLUS, 0), 0.02)
    hover_out(s)

def interpola(a, b, n):
    return [(a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n) for i in range(n + 1)]

def scrivi(s, X, Y, altezza, testo):
    """Scrive testo in corsivo con un font a tratto singolo, come farebbe una mano."""
    from HersheyFonts import HersheyFonts
    f = HersheyFonts()
    f.load_default_font("cursive")
    f.normalize_rendering(altezza)
    for i, tratto in enumerate(f.strokes_for_text(testo)):
        pts = [(X + x, Y - y) for x, y in tratto]
        seq = []
        for a, b in zip(pts, pts[1:]):
            seq += interpola(a, b, max(1, int(math.dist(a, b) / 4)))[:-1]
        seq.append(pts[-1])
        n = len(seq)
        # Pressione che sale all'inizio, ondeggia e cala alla fine del tratto.
        punti = [(x, y, min(1.0, 0.25 + 0.5 * math.sin(math.pi * k / max(1, n - 1)) + 0.12 * math.sin(k / 3 + i))) for k, (x, y) in enumerate(seq)]
        stroke(s, punti, tilt=(22, -14))

TOUCH = "/dev/input/event1"
ABS_MT_SLOT, ABS_MT_TRACKING_ID, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_PRESSURE, ABS_MT_TOUCH_MAJOR = 0x2f, 0x39, 0x35, 0x36, 0x3a, 0x30

class ScriptDita(Script):
    """Come Script, ma scrive nel touchscreen (dita)."""
    def run(self):
        global DEV
        vecchio, DEV = DEV, TOUCH
        try:
            super().run()
        finally:
            DEV = vecchio

def dita(s, percorsi, passi=24, pausa=0.012):
    """percorsi: per ogni dito ((x0,y0),(x1,y1)); le dita si muovono insieme."""
    ids = list(range(len(percorsi)))
    for k in range(passi + 1):
        f = k / passi
        data = b""
        for i, ((x0, y0), (x1, y1)) in enumerate(percorsi):
            data += ev(EV_ABS, ABS_MT_SLOT, i)
            if k == 0:
                data += ev(EV_ABS, ABS_MT_TRACKING_ID, 100 + i) + ev(EV_ABS, ABS_MT_TOUCH_MAJOR, 8) + ev(EV_ABS, ABS_MT_PRESSURE, 40)
            data += ev(EV_ABS, ABS_MT_POSITION_X, int(x0 + (x1 - x0) * f)) + ev(EV_ABS, ABS_MT_POSITION_Y, int(y0 + (y1 - y0) * f))
        if k == 0:
            data += ev(EV_KEY, BTN_TOUCH, 1)
        s.frame(data, pausa)
    data = b""
    for i in ids:
        data += ev(EV_ABS, ABS_MT_SLOT, i) + ev(EV_ABS, ABS_MT_TRACKING_ID, -1)
    s.frame(data + ev(EV_KEY, BTN_TOUCH, 0), 0.05)

def main(argv):
    s = Script()
    cmd = argv[0]
    opzioni = [a for a in argv[1:] if a.startswith("--")]
    argv = [argv[0]] + [a for a in argv[1:] if not a.startswith("--") and not _valore_opzione(argv, a)]
    pressione = float(_opzione(opzioni, argv, "--pressione", "0.6"))
    if cmd == "tratto":
        pts = [tuple(map(float, a.split(","))) for a in argv[1:]]
        seq = []
        for a, b in zip(pts, pts[1:]):
            seq += interpola(a, b, max(4, int(math.dist(a, b) / 6)))
        stroke(s, [(x, y, pressione) for x, y in seq], tasto="--tasto" in opzioni)
    elif cmd == "scorri":
        x0, y0, x1, y1 = map(float, argv[1:5])
        s = ScriptDita()
        dita(s, [((x0, y0), (x1, y1))])
    elif cmd == "pizzica":
        cx, cy, d0, d1 = map(float, argv[1:5])
        s = ScriptDita()
        dita(s, [((cx - d0 / 2, cy), (cx - d1 / 2, cy)), ((cx + d0 / 2, cy), (cx + d1 / 2, cy))], passi=30)
    elif cmd == "scrivi":
        X, Y, H = map(float, argv[1:4])
        scrivi(s, X, Y, H, " ".join(argv[4:]))
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

_VALORI = {}

def _valore_opzione(argv, a):
    """Vero se [a] e' il valore di un'opzione (es. 0.3 dopo --pressione)."""
    i = argv.index(a)
    return i > 0 and argv[i - 1] == "--pressione"

def _opzione(opzioni, argv, nome, predefinito):
    import sys as _s
    tutti = _s.argv[1:]
    if nome in tutti:
        return tutti[tutti.index(nome) + 1]
    return predefinito

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
