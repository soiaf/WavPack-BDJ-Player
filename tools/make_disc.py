#!/usr/bin/env python3
"""
Build a minimal BD-J Blu-ray folder (BDMV) that auto-starts one xlet.

    python3 make_disc.py <out_dir> <signed.jar> --keys <keys_dir> [--font <font.otf>]
                         [--class <initial xlet class>] [--copy SRC=DEST_REL_TO_DISC ...]
                         [music files ...]

Creates:
    BDMV/index.bdmv            first-play, top-menu and title 1 -> BD-J object 00000
    BDMV/MovieObject.bdmv      empty (no HDMV objects)
    BDMV/BDJO/00000.bdjo       one auto-start xlet, wvplay.WvPlayerXlet, from 00000.jar
    BDMV/JAR/00000.jar
    BDMV/JAR/00000/music/      the .wv files
    BDMV/BACKUP/...            copies of the above, as the spec requires
    BDMV/AUXDATA/00000.otf     default font + dvb.fontindex (if --font given)
    CERTIFICATE/               app.discroot.crt, bu.discroot.crt, id.bdmv (+ BACKUP copies)

Then re-parses every generated file with a Python port of libbluray's parsers
(index_parse.c, mobj_parse.c, bdjo_parse.c) to make sure they read back.
"""
import os
import shutil
import struct
import sys

INITIAL_CLASS = "wvplay.WvPlayerXlet"
ORG_ID = 0x7FFF0001          # replaced by keys/orgid.txt in main()
FONT_NAME = "RobotoMono"
APP_ID = 0x4000
DISC_ID = "57765061636b42444a54657374303031"   # "WvPackBDJTest001"

# --------------------------------------------------------------------------- writers


def index_bdmv():
    def bdj_obj(name):                       # 8 bytes
        return struct.pack(">H5sB", 3 << 14, name.encode(), 0)   # playback type 3 = interactive

    def play_obj(name):                      # 12 bytes: object type 2 = BD-J
        return struct.pack(">I", 2 << 30) + bdj_obj(name)

    def title(name):                         # 12 bytes: type BD-J, access permitted
        return struct.pack(">I", (2 << 30) | (0 << 28)) + bdj_obj(name)

    # initial output mode: 1080i 29.97 (same as the clips). 1080p 24 Hz made some players
    # (Oppo UDP-203) switch HDMI to 24 Hz at disc start, which many monitors can't show.
    app_info = struct.pack(">I", 34) + bytes([0x00, (4 << 4) | 4]) + bytes(32)
    body = play_obj("00000") + play_obj("00000") + struct.pack(">H", 1) + title("00000")
    indexes = struct.pack(">I", len(body)) + body

    index_start = 40 + len(app_info)
    hdr = b"INDX0200" + struct.pack(">II", index_start, 0) + bytes(24)
    assert len(hdr) == 40
    return hdr + app_info + indexes


def id_bdmv():
    """CERTIFICATE/id.bdmv: organisation ID (must match the BDJO) + 16-byte disc ID."""
    hdr = b"BDID0200" + struct.pack(">II", 40, 0) + bytes(24)
    return hdr + struct.pack(">I", ORG_ID) + bytes.fromhex(DISC_ID)


def movieobject_bdmv():
    hdr = b"MOBJ0200" + struct.pack(">I", 0) + bytes(28)
    assert len(hdr) == 40
    return hdr + struct.pack(">IIH", 6, 0, 0)


DEFAULT_FONT = b"*****"       # "00000" when a disc font is included


def _app_string(s):
    """length byte + string, padded to an even total (libbluray _read_app_string)."""
    b = s.encode()
    out = bytes([len(b)]) + b
    if len(b) % 2 == 0:
        out += b"\0"
    return out


def bdjo():
    terminal = DEFAULT_FONT + bytes([1 << 4]) + bytes(4)  # default font; HAVi config 1 = 1920x1080
    terminal = struct.pack(">I", len(terminal)) + terminal

    cache_items = bytes([1]) + b"00000" + b"*.*" + bytes(3)        # type 1 = JAR file
    cache = struct.pack(">I", 2 + len(cache_items)) + bytes([1, 0]) + cache_items

    playlists = struct.pack(">I", 4) + struct.pack(">I", 1 << 20)   # 0 listed, access_to_all = 1

    names = struct.pack(">H", 0)                      # no application names

    app_desc = (
        struct.pack(">H", 1 << 12)                    # 1 profile
        + struct.pack(">HBBBB", 1, 1, 0, 0, 0)        # profile 1, version 1.0.0
        + bytes([3])                                  # priority
        + bytes([(3 << 6) | (1 << 4)])                # binding: title+disc bound; visibility 1
        + names
        + _app_string("")                             # icon locator
        + struct.pack(">H", 0)                        # icon flags
        + _app_string("00000")                        # base directory
        + _app_string("")                             # classpath extension
        + _app_string(INITIAL_CLASS)                  # initial class
        + bytes([0, 0])                               # no parameters (+ pad)
    )
    app = (
        bytes([1])                                    # control code 1 = AUTOSTART
        + bytes([1 << 4])                             # application type 1
        + struct.pack(">IH", ORG_ID, APP_ID)
        + bytes([0x00, 0x00]) + struct.pack(">I", len(app_desc) + 4) + bytes(4)   # descriptor header; length counts the 4 reserved bytes too (matches reference)
        + app_desc
    )
    amt_body = bytes([1, 0]) + app
    amt = struct.pack(">I", len(amt_body)) + amt_body

    keys = struct.pack(">I", 0)
    file_access = struct.pack(">H", 1) + b"." + b"\0"   # whole disc readable (+ pad to even)

    sections = [terminal, cache, playlists, amt, keys, file_access]
    off = 48
    addrs = []
    for s in sections:
        addrs.append(off)
        off += len(s)
    # address table: 6 section starts + extension data start (0) + 12 reserved bytes = 40 bytes
    hdr = b"BDJO0200" + struct.pack(">7I", *(addrs + [0])) + bytes(12)
    assert len(hdr) == 48
    return hdr + b"".join(sections)

# --------------------------------------------------------------------------- checker (port of libbluray)


class Bits:
    def __init__(self, data):
        self.d, self.p = data, 0

    def read(self, n):
        v = 0
        for _ in range(n):
            if self.p >= len(self.d) * 8:
                raise EOFError("read past end")
            byte = self.d[self.p >> 3]
            v = (v << 1) | ((byte >> (7 - (self.p & 7))) & 1)
            self.p += 1
        return v

    def skip(self, n):
        self.p += n

    def seek_byte(self, b):
        self.p = b * 8

    def string(self, n):
        return bytes(self.read(8) for _ in range(n)).decode("latin1")

    def left_bytes(self):
        return len(self.d) - self.p // 8


def check_index(data):
    bs = Bits(data)
    assert bs.string(4) == "INDX" and bs.string(4) in ("0100", "0200")
    index_start = bs.read(32)
    bs.read(32)
    bs.seek_byte(40)
    assert bs.read(32) == 34, "app_info length"
    bs.seek_byte(index_start)
    index_len = bs.read(32)
    assert bs.left_bytes() >= index_len, "index_len"

    def bdj():
        pt = bs.read(2); bs.skip(14); name = bs.string(5); bs.skip(8)
        assert pt in (2, 3), "bdj playback type"
        return name

    out = {}
    for which in ("first_play", "top_menu"):
        t = bs.read(2); bs.skip(30)
        assert t == 2, which + " object type"
        out[which] = bdj()
    n = bs.read(16)
    assert n > 0, "empty index"
    out["titles"] = []
    for _ in range(n):
        t = bs.read(2); bs.read(2); bs.skip(28)
        assert t == 2
        out["titles"].append(bdj())
    return out


def check_mobj(data):
    bs = Bits(data)
    assert bs.string(4) == "MOBJ" and bs.string(4) in ("0100", "0200")
    bs.seek_byte(40)
    ln = bs.read(32)
    assert bs.left_bytes() >= ln
    bs.skip(32)
    return {"objects": bs.read(16)}


def check_bdjo(data):
    bs = Bits(data)
    assert bs.string(4) == "BDJO" and bs.string(4) in ("0100", "0200")
    addrs = [bs.read(32) for _ in range(7)]
    bs.seek_byte(48)
    out = {}
    # terminal info
    assert bs.p // 8 == addrs[0]
    bs.skip(32); out["font"] = bs.string(5); out["havi"] = bs.read(4)
    assert 1 <= out["havi"] <= 6, "invalid HAVi configuration id"; bs.skip(1 + 1 + 34)
    # app cache
    assert bs.p // 8 == addrs[1]
    bs.skip(32); n = bs.read(8); bs.skip(8)
    out["cache"] = []
    for _ in range(n):
        t = bs.read(8); name = bs.string(5); bs.string(3); bs.skip(24)
        out["cache"].append((t, name))
    # playlists
    assert bs.p // 8 == addrs[2]
    bs.skip(32); npl = bs.read(11); out["access_all"] = bs.read(1); bs.read(1); bs.skip(19)
    for _ in range(npl):
        bs.string(5); bs.skip(8)
    # app management table
    assert bs.p // 8 == addrs[3]
    bs.skip(32); napp = bs.read(8); bs.skip(8)
    out["apps"] = []
    for _ in range(napp):
        app = {"control": bs.read(8), "type": bs.read(4)}
        bs.skip(4)
        app["org"] = bs.read(32); app["id"] = bs.read(16)
        bs.skip(80)
        nprof = bs.read(4); bs.skip(12)
        for _ in range(nprof):
            bs.read(16); bs.read(8); bs.read(8); bs.read(8); bs.skip(8)
        bs.read(8); bs.read(2); bs.read(2); bs.skip(4)
        dl = bs.read(16); start = bs.p
        names = []
        while (bs.p - start) // 8 < dl:
            bs.string(3); names.append(bs.string(bs.read(8)))
        assert (bs.p - start) // 8 == dl, "names length"
        bs.skip(8 * (dl & 1))

        def app_string():
            ln = bs.read(8); s = bs.string(ln)
            if not (ln & 1):
                bs.skip(8)
            return s
        app["names"] = names
        app["icon"] = app_string(); bs.read(16)
        app["base"] = app_string(); app["cp"] = app_string(); app["class"] = app_string()
        pl = bs.read(8); bs.skip(8 * pl)
        if not (pl & 1):
            bs.skip(8)
        out["apps"].append(app)
    assert bs.p // 8 == addrs[4]
    bs.read(32)
    assert bs.p // 8 == addrs[5]
    fl = bs.read(16); out["file_access"] = bs.string(fl)
    assert bs.left_bytes() <= 1, "trailing bytes"
    return out

# --------------------------------------------------------------------------- main


def main():
    global ORG_ID, DEFAULT_FONT, INITIAL_CLASS
    args = sys.argv[1:]
    keys = font = None
    copies = []
    while "--copy" in args:
        i = args.index("--copy"); copies.append(args[i + 1].split("=", 1)); del args[i:i + 2]
    global DISC_ID
    if "--disc-id" in args:
        i = args.index("--disc-id"); DISC_ID = args[i + 1].encode().hex() if len(args[i + 1]) == 16 else args[i + 1]
        del args[i:i + 2]
        assert len(DISC_ID) == 32, "disc id: 16 ASCII characters or 32 hex digits"
    if "--class" in args:
        i = args.index("--class"); INITIAL_CLASS = args[i + 1]; del args[i:i + 2]
    if "--keys" in args:
        i = args.index("--keys"); keys = args[i + 1]; del args[i:i + 2]
    if "--font" in args:
        i = args.index("--font"); font = args[i + 1]; del args[i:i + 2]
    if len(args) < 2 or not keys:
        print(__doc__)
        sys.exit(1)
    out, jar, music = args[0], args[1], args[2:]
    ORG_ID = int(open(os.path.join(keys, "orgid.txt")).read().strip(), 16)
    if font:
        DEFAULT_FONT = b"00000"

    bdmv = os.path.join(out, "BDMV")
    for d in ("BDJO", "JAR/00000/music", "PLAYLIST", "CLIPINF", "STREAM", "AUXDATA", "META",
              "BACKUP/BDJO", "BACKUP/PLAYLIST", "BACKUP/CLIPINF"):
        os.makedirs(os.path.join(bdmv, d), exist_ok=True)
    os.makedirs(os.path.join(out, "CERTIFICATE", "BACKUP"), exist_ok=True)

    files = {
        "index.bdmv": index_bdmv(),
        "MovieObject.bdmv": movieobject_bdmv(),
        "BDJO/00000.bdjo": bdjo(),
    }
    for rel, data in files.items():
        for base in (bdmv, os.path.join(bdmv, "BACKUP")):
            with open(os.path.join(base, rel), "wb") as f:
                f.write(data)
    for base in ("CERTIFICATE", "CERTIFICATE/BACKUP"):
        with open(os.path.join(out, base, "id.bdmv"), "wb") as f:
            f.write(id_bdmv())
        for crt in ("app.discroot.crt", "bu.discroot.crt"):
            shutil.copyfile(os.path.join(keys, crt), os.path.join(out, base, crt))
    if font:
        shutil.copyfile(font, os.path.join(bdmv, "AUXDATA", "00000.otf"))
        with open(os.path.join(bdmv, "AUXDATA", "dvb.fontindex"), "w", newline="\n") as f:
            f.write(FONT_INDEX % FONT_NAME)
    shutil.copyfile(jar, os.path.join(bdmv, "JAR", "00000.jar"))
    for src, dest in copies:
        target = os.path.join(out, dest)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        shutil.copyfile(src, target)
    names = []
    for m in sorted(music):
        shutil.copyfile(m, os.path.join(bdmv, "JAR", "00000", "music", os.path.basename(m)))
        names.append(os.path.basename(m))
    with open(os.path.join(bdmv, "JAR", "00000", "music", "tracks.txt"), "w", newline="\n") as f:
        f.write("".join(n + "\n" for n in names))

    print("index.bdmv      ", check_index(files["index.bdmv"]))
    print("MovieObject.bdmv", check_mobj(files["MovieObject.bdmv"]))
    print("00000.bdjo      ", check_bdjo(files["BDJO/00000.bdjo"]))
    print("OK:", out)


FONT_INDEX = """<?xml version="1.0"?>
<!DOCTYPE fontdirectory PUBLIC "-//DVB//DTD Font Directory 1.0//EN"
"http://www.dvb.org/mhp/dtd/fontdirectory-1-0.dtd">
<fontdirectory>
  <font>
    <name>%s</name>
    <fontformat>OTF</fontformat>
    <filename>00000.otf</filename>
    <style>PLAIN</style>
  </font>
</fontdirectory>
"""


if __name__ == "__main__":
    main()
