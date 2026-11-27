#!/bin/sh
# Build the WavPack player disc folder (progressive version) and the PC library tool.
#   ./build_player.sh <Java-WavPack-Decoder dir (v1.1 or later)> <bdj-sdk dir> [out-dir] [music dir] [slot 1-99]
# The disc folder gets the signed xlet; the music library (layout, clip info,
# signed manifest) is made by wvlibrary.jar, which this script also builds and,
# if a music dir is given, runs on the new disc folder.
# Each slot has its own disc ID, so discs from different slots keep separate libraries on the player.
set -e
DEC=${1:?Java-WavPack-Decoder directory}
SDK=${2:?bdj-sdk directory}
OUT=${3:-out/WVPLAYER}
MUSIC=${4:-}
HERE=$(cd "$(dirname "$0")" && pwd)
TOP=$(cd "$HERE/.." && pwd)
B="$HERE/build"
BDJ="$SDK/target/lib/enhanced-stubs.zip"
SLOT=${5:-1}
DISC_ID=$(printf "WvPackPlayer%04d" "$SLOT")

rm -rf "$B" && mkdir -p "$B/cstub" "$B/classes" "$B/tool" "$B/vfs"
echo "slot $SLOT, disc id $DISC_ID"
javac -nowarn -source 8 -target 8 -bootclasspath "$BDJ" -d "$B/cstub" "$TOP/compilestub/java/lang/StringBuilder.java"
javac -nowarn -source 8 -target 8 -bootclasspath "$B/cstub:$BDJ" -d "$B/classes" \
      $(find "$DEC/com" "$TOP/bdclip/src" "$HERE/src" -name '*.java')
python3 "$TOP/tools/retro.py" "$B/classes"
python3 "$TOP/tools/apicheck.py" "$BDJ" "$B/classes"
# discs may only read the bluray.* system properties: anything else throws on real players
if grep -rnE 'Integer\.getInteger|Long\.getLong|Boolean\.getBoolean|getProperty\("[^b]' "$HERE/src" "$TOP/bdclip/src" \
   | grep -v 'getProperty("dvb.persistent.root")'; then
    echo "ERROR: system property read in disc code (not allowed on players)"; exit 1
fi
cp "$HERE/src/wvplayer/bluray.PlayerXlet.perm" "$B/classes/wvplayer/"
(cd "$B/classes" && jar cfM "$B/unsigned.jar" wvplayer bdclip com)
# signing keys: made once (not part of the repository), then reused for every build
[ -f "$TOP/keys/app.discroot.crt" ] || python3 "$TOP/tools/bdsign.py" genkeys "$TOP/keys" 7fff0001
python3 "$TOP/tools/bdsign.py" sign "$TOP/keys" "$B/unsigned.jar" "$B/00000.jar"
python3 "$TOP/tools/bdsign.py" verify "$TOP/keys" "$B/00000.jar"

# PC library tool (Java 11+): layout + clip info + signed manifest, from the music on a disc folder
S="$HERE/src/wvplayer"
javac -nowarn --release 11 -d "$B/tool" $(find "$DEC/com" "$TOP/bdclip/src" -name '*.java') \
      "$S/Catalog.java" "$S/Library.java" "$S/Layout.java" "$S/Tags.java" "$S/WvSeek.java" \
      "$HERE/tools/wvplayer/MakeLibrary.java"
openssl pkcs8 -topk8 -nocrypt -in "$TOP/keys/bu.key.pem" -outform DER -out "$B/tool/bu.key.der"
printf 'Main-Class: wvplayer.MakeLibrary\n' > "$B/tool.mf"
(cd "$B/tool" && jar cfm "$B/wvlibrary.jar" "$B/tool.mf" .)

cp "$HERE/assets/black1080i.m2v" "$B/vfs/"
rm -rf "$OUT"
python3 "$TOP/tools/make_disc.py" "$OUT" "$B/00000.jar" --keys "$TOP/keys" --disc-id "$DISC_ID" \
        --font "$TOP/assets/RobotoMono-Regular.otf" --class wvplayer.PlayerXlet \
        --copy "$B/vfs/black1080i.m2v=BDMV/JAR/00000/vfs/black1080i.m2v"
rm -f "$OUT/BDMV/JAR/00000/music/tracks.txt"
# options the listener can edit on the disc folder before making the ISO
printf '# WavPack player options\r\n# diagnostics=on shows the log and speed lines from the start\r\n# (they can also be switched on/off in the album menu, and switch on by\r\n# themselves when something goes wrong)\r\ndiagnostics=off\r\n' > "$OUT/BDMV/JAR/00000/options.txt"
# silent clip the player runs between songs (keeps the video going: no black screen)
mkdir -p "$B/idle"
javac -nowarn --release 11 -d "$B/idle" "$TOP/bdclip/src/bdclip/"*.java "$HERE/tools/wvplayer/IdleClip.java"
java -cp "$B/idle" wvplayer.IdleClip "$OUT/BDMV" 20 "$HERE/assets/black1080i.m2v"
if [ -n "$MUSIC" ]; then
    cp -r "$MUSIC"/. "$OUT/BDMV/JAR/00000/music/"
    java -jar "$B/wvlibrary.jar" "$OUT"
fi
echo "Player disc folder ready: $OUT   (library tool: $B/wvlibrary.jar)"
