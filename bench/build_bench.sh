#!/bin/sh
# Benchmark disc.  ./build_bench.sh <Java-WavPack-Decoder dir> <bdj-sdk dir> [out-dir]
set -e
DEC=${1:?decoder dir}; SDK=${2:?bdj-sdk dir}; OUT=${3:-out/WVBENCH}
HERE=$(cd "$(dirname "$0")" && pwd); TOP=$(cd "$HERE/.." && pwd); B="$HERE/build"
BDJ="$SDK/target/lib/enhanced-stubs.zip"
rm -rf "$B" && mkdir -p "$B/cstub" "$B/classes" "$B/files"
javac -nowarn -source 8 -target 8 -bootclasspath "$BDJ" -d "$B/cstub" "$TOP/compilestub/java/lang/StringBuilder.java"
javac -nowarn -source 8 -target 8 -bootclasspath "$B/cstub:$BDJ" -d "$B/classes" \
      $(find "$DEC/com" "$TOP/bdclip/src" "$HERE/src" -name '*.java')
cp "$HERE/src/wvbench/bluray.BenchXlet.perm" "$B/classes/wvbench/"
python3 "$TOP/tools/retro.py" "$B/classes"
python3 "$TOP/tools/apicheck.py" "$BDJ" "$B/classes"
(cd "$B/classes" && jar cfM "$B/unsigned.jar" .)
# signing keys: made once (not part of the repository), then reused for every build
[ -f "$TOP/keys/app.discroot.crt" ] || python3 "$TOP/tools/bdsign.py" genkeys "$TOP/keys" 7fff0001
python3 "$TOP/tools/bdsign.py" sign "$TOP/keys" "$B/unsigned.jar" "$B/00000.jar"
python3 "$TOP/tools/bdsign.py" verify "$TOP/keys" "$B/00000.jar"
ffmpeg -nostdin -hide_banner -v error -y -f lavfi -i "anoisesrc=r=44100:d=12:c=pink:a=0.3,aformat=channel_layouts=stereo" -c:a wavpack -sample_fmt s16p "$B/files/t16.wv"
ffmpeg -nostdin -hide_banner -v error -y -f lavfi -i "anoisesrc=r=44100:d=12:c=pink:a=0.3,aformat=channel_layouts=stereo" -c:a wavpack -sample_fmt s32p "$B/files/t32.wv"
C=""
for f in t16.wv t32.wv; do C="$C --copy $B/files/$f=BDMV/JAR/00000/bench/$f"; done
C="$C --copy $TOP/player/assets/black1080i.m2v=BDMV/JAR/00000/bench/black1080i.m2v"
rm -rf "$OUT"
python3 "$TOP/tools/make_disc.py" "$OUT" "$B/00000.jar" --keys "$TOP/keys" --disc-id WvPackBenchDisc2 \
        --font "$TOP/assets/RobotoMono-Regular.otf" --class wvbench.BenchXlet $C
rm -rf "$OUT/BDMV/JAR/00000/music"
# clip 00001 for the playback tests (same format as the player's own streams)
mkdir -p "$B/tool"
javac -nowarn -d "$B/tool" "$TOP/bdclip/src/bdclip/"*.java "$HERE/tools/BenchClip.java"
java -cp "$B/tool" BenchClip "$OUT/BDMV" 240 "$TOP/player/assets/black1080i.m2v"
echo "Benchmark disc folder ready: $OUT"
