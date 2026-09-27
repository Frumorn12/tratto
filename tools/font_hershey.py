#!/usr/bin/env python3
"""Genera app/src/completa/assets/scrittura/hershey.bin, i font a tratto singolo della "bella scrittura".

Prende i glifi ASCII 32..126 di due font Hershey dal pacchetto Python Hershey-Fonts
(pip install Hershey-Fonts):
  - "cursive"  -> corsivo (lo stesso usato da tools/penna.py per simulare la scrittura a mano);
  - "futural"  -> stampatello (Futura light: lettere identiche a "rowmans", punteggiatura
                  piu' stretta e vicina alla lettera, come quando si scrive a mano).

Le coordinate sono quelle originali di Hershey: interi piccoli, origine al centro del glifo,
y verso il basso (come le unita' di pagina di Tratto). Formato del file (big endian, byte con segno):

  "THF1"
  u8 numero di font
  per ogni font:
    u8 stile (0 = corsivo, 1 = stampatello)
    i8 linea delle maiuscole, i8 linea delle x (cima delle minuscole), i8 linea di base, i8 fondo dei discendenti
    u8 primo codice (32), u8 numero di glifi
    per ogni glifo: i8 margine sinistro, i8 margine destro, u8 numero di tratti
      per ogni tratto: u8 numero di punti, poi i punti come coppie (i8 x, i8 y)

Uso: tools/font_hershey.py [file di uscita]
Attribuzione e condizioni d'uso dei font: docs/Hershey-fonts.txt
"""
import os
import struct
import sys

from HersheyFonts import HersheyFonts

FONT = [(0, "cursive"), (1, "futural")]
PRIMO, ULTIMO = 32, 126
USCITA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "completa", "assets", "scrittura", "hershey.bin")


def i8(v):
    v = int(v)
    if not -128 <= v <= 127:
        raise ValueError(f"valore fuori da un byte: {v}")
    return struct.pack(">b", v)


def u8(v):
    v = int(v)
    if not 0 <= v <= 255:
        raise ValueError(f"valore fuori da un byte: {v}")
    return struct.pack(">B", v)


def font(stile, nome):
    f = HersheyFonts()
    f.load_default_font(nome)
    glifi = f.all_glyphs
    ro = f.render_options
    # Cima delle minuscole: la "x" non ha ne' aste ne' puntini.
    linea_x = glifi["x"].draw_box[0][1]
    # Fondo dei discendenti: il punto piu' basso tra le lettere con la coda.
    fondo = max(glifi[c].draw_box[1][1] for c in "gjpqy")
    out = [u8(stile), i8(ro["cap_line"]), i8(linea_x), i8(ro["base_line"]), i8(fondo), u8(PRIMO), u8(ULTIMO - PRIMO + 1)]
    for codice in range(PRIMO, ULTIMO + 1):
        g = glifi[chr(codice)]
        tratti = [t for t in g.strokes if len(t) > 0]
        out.append(i8(g.left_offset) + i8(g.left_offset + g.char_width) + u8(len(tratti)))
        for t in tratti:
            out.append(u8(len(t)))
            out.extend(i8(x) + i8(y) for x, y in t)
    return b"".join(out)


def main(argv):
    uscita = argv[0] if argv else USCITA
    dati = b"THF1" + u8(len(FONT)) + b"".join(font(s, n) for s, n in FONT)
    os.makedirs(os.path.dirname(os.path.abspath(uscita)), exist_ok=True)
    with open(uscita, "wb") as f:
        f.write(dati)
    print(f"{uscita}: {len(dati)} byte")


if __name__ == "__main__":
    main(sys.argv[1:])
