# WavPack BD-J Player

This project helps produce a Blu-ray disc that contains a music player written
in Java (BD-J) that can play **WavPack** (`.wv`) files.
See [www.wavpack.com](https://www.wavpack.com) for more details about the WavPack format itself.
While the actual Java code used for the WavPack decoder was written by me, I
used AI assistance (Claude) when developing the remainder of the solution.

The disc carries your `.wv` files and a Java (BD-J) program. The program decodes
the music **on the player**, using the
[Java WavPack decoder](https://github.com/soiaf/Java-WavPack-Decoder), and plays it
through the player's normal Blu-ray audio path: full quality, gapless, one chapter
per track. Playback starts a couple of seconds after you pick a track. The rest of
the album is decoded in the background while you listen.

## Features

- Albums and tracks with cover art, browsed with the remote
- Gapless playback within an album; NEXT/PREVIOUS and chapter skipping
- 16/24/32-bit WavPack, mono or stereo, 44.1 to 192 kHz. 48/96/192 kHz music is
  played bit-exact. 44.1/88.2/176.4 kHz is resampled to 48/96/192 kHz with a
  64-tap windowed-sinc filter.
- Decoded music is kept in the player's BD-Live storage, so an album played once
  starts instantly next time. When storage fills up, the album played longest ago
  is released.
- Optional "decode all albums in the background" mode
- Diagnostic messages that can be switched on and off from the menu

## Players

| Player | Status |
|---|---|
| Sony PlayStation 3 | **Works** |
| Oppo UDP-203 | **Works**. Select a USB stick as its storage (see the user guide). It decodes 32-bit 44.1 kHz files at about 4.7x real time. |
| Panasonic DMP-UB900 | **Does not work.** It runs the program but doesn't let a home-made disc use its storage; it seems to allow that only for commercial copy-protected discs. The disc says so on screen. |

Other players are untested. The disc needs a player with **BD-Live (BD-J Profile 2)
local storage** that lets a self-signed disc use it, and about 1 GB of storage per
hour of music. Reports from other players are welcome, as issues.

This project doesn't use, bypass or interfere with any copy protection. The discs
are ordinary unprotected BD-ROM (BDMV) discs.

## Using it

Download a ready-made package from the **Releases** page. It holds ten empty disc
folders, the PC library tool and a Windows helper. Then follow
**[the user guide](docs/USER_GUIDE.md)**:

1. Copy your music (in `Artist\Album\` folders) into a disc folder.
2. Run the library tool on it. It needs Java 11 or later on the PC.
3. Make an ISO (UDF 2.50) and burn it, e.g. with ImgBurn.

## How it works

1. **Library tool (on the PC).** `wvlibrary.jar` reads each track's header (it
   doesn't decode the audio). It plans how each album's audio will be cut into
   chunks of 10 to 60 seconds, and writes the Blu-ray clip information and
   playlists for those chunks in advance. It also prepares the covers and signs a
   manifest for the disc's storage area (a BD-Live "binding unit").
2. **First insert.** The program copies the clip information into the player's
   storage and asks the player to add it to the disc (a BD "virtual package"
   update). Each album then appears as a playlist whose chunks don't exist yet.
3. **Playing.** The program decodes the album with the WavPack decoder. It resamples
   if needed, writes each chunk as an MPEG-2 transport stream (24-bit LPCM audio,
   plus a black video frame the format requires) into the storage, and then
   *enables* the chunk. The player plays the playlist as a **progressive
   playlist**: it waits for any chunk that isn't enabled yet, then carries on
   seamlessly. Chunk boundaries fall on exact audio frames, and the stream writer
   can resume from any chunk, so decoding can start mid-album and stays
   bit-identical.
4. **Between songs** the player runs a short silent clip, so the video never stops.
   Switching it to a song (`PlayListChangeControl`) doesn't blank the screen.

Everything the disc runs is Java 1.3-level code. It's compiled against the BD-J
platform API, converted to Java 1.3 class files, and checked so that it only uses
classes and methods the BD-J API has.

## Building from source

On Linux, macOS or Windows (WSL), you need:

- a JDK 11 to 21 (`javac`, `jar`)
- Python 3 with the `cryptography` package
- `openssl`
- `ffmpeg` (only for the benchmark disc's test files)
- a clone of the [Java WavPack decoder](https://github.com/soiaf/Java-WavPack-Decoder), v1.1 or later
- a clone of [bdj-sdk](https://github.com/john-tornblom/bdj-sdk). Only its
  `target/lib/enhanced-stubs.zip` (the BD-J platform API) is used, to compile against.

Then:

    player/build_player.sh <decoder dir> <bdj-sdk dir> [out dir] [music dir] [slot 1-99]

The script:

- compiles the player
- converts it to Java 1.3 classes
- checks every API call against BD-J
- signs the jar and verifies the signature
- builds the PC library tool `player/build/wvlibrary.jar`
- writes a disc folder, with disc ID `WvPackPlayer<slot>`

If a music folder is given, it also runs the library tool on it.

**Signing keys.** The first build creates a key set in `keys/`, with org ID
`7fff0001`. Keep that folder safe and reuse it: every disc must be signed with the
same keys. Players file each disc's decoded music under the disc's certificate
and disc ID, so new keys look like a different disc to them. The keys are not in
the repository (see `.gitignore`). They can't be truly secret anyway: the library
tool has to sign each disc's manifest on the user's PC, so the released
`wvlibrary.jar` contains the signing key. They only control this disc's own
storage area on a player.

`bench/build_bench.sh <decoder dir> <bdj-sdk dir> [out dir]` builds a **benchmark
disc**. It measures on a player how fast its Java runs the work the player does:
decoding, resampling, stream writing, storage writes, and decoding while a clip
plays. That makes it useful for checking a new player.

## Repository layout

| Folder | Contents |
|---|---|
| `player/src` | the disc program: user interface, playback, background chunk writer, storage management |
| `player/tools` | the PC library tool (`MakeLibrary`), and the build-time tool for the silent clip |
| `player/windows` | `make-library.bat` (drag a disc folder onto it) |
| `bdclip/src` | MPEG-2 transport stream writer (LPCM + video, Blu-ray clip info and playlists, resumable chunks) and the resampler |
| `bench` | the benchmark disc |
| `tools` | Python build tools: disc folder writer, BD-J signer, Java 1.3 class converter, BD-J API checker |
| `compilestub` | a minimal `StringBuilder`, so string concatenation compiles to code the Java 1.3 conversion can handle |
| `assets` | the Roboto Mono font used on the disc |

## Credits and licences

- This project: BSD licence, see [LICENSE](LICENSE).
- [Java WavPack decoder](https://github.com/soiaf/Java-WavPack-Decoder), by the same
  author (BSD). It is based on the tiny WavPack decoder by David Bryant / Conifer
  Software.
- [Roboto Mono](https://github.com/googlefonts/robotomono): Apache License 2.0, see
  `assets/LICENSE-RobotoMono.txt`.
- The BD-J platform API stubs come from [bdj-sdk](https://github.com/john-tornblom/bdj-sdk),
  used at build time only and not included. The certificate profile follows the
  HD Cookbook's BDCertGenerator.
