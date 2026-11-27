# WavPack BD-J Player: user guide

A Blu-ray disc that plays your WavPack (`.wv`) files. The disc's Java program
decodes the music on the player and plays it through the player's normal Blu-ray
audio path, gapless and at full quality, with a chapter per track. Pick a track
and it starts in a couple of seconds. The rest of the album is decoded in the
background while you listen, and everything decoded is kept in the player's
storage, so an album you've played once starts instantly next time.

## What's in the release package

    WVPLAYER-01 ... WVPLAYER-10   ten disc folders ("slots"), no music yet
    wvlibrary.jar                 the library tool: run it after adding music (needs Java 11 or later)
    make-library.bat              Windows: drag a disc folder onto this to run the tool
    README.md                     this guide

Each slot has its own disc ID, so a player keeps a separate library for each.
**Use a different slot for each disc** you want to keep around. If you burn a
disc with different music into a slot that was used before, the player clears
that slot's old library the first time the new disc goes in.

## Making a disc (Windows)

1. Pick a slot folder, e.g. `WVPLAYER-01`.
2. Copy your music into `WVPLAYER-01\BDMV\JAR\00000\music\`, **keeping your
   `Artist\Album\tracks` folders**, for example:

       music\Alpha Band\First Light\01 Dawn.wv
       music\Alpha Band\First Light\cover.jpg
       music\Beta Ensemble\Grand Set\CD1\01 Overture.wv

   * Every folder that contains `.wv` files is an album (up to 900 per disc).
     A multi-disc set with `CD1`/`CD2` subfolders shows as two albums.
   * Tracks play in file-name order (`01 ...`, `02 ...`).
   * Plain ASCII file and folder names are safest.
3. **Run the library tool.** Drag the `WVPLAYER-01` folder onto
   `make-library.bat`, or in a Command Prompt:

       java -jar wvlibrary.jar WVPLAYER-01

   It reads each track's header (it doesn't decode the audio), prepares the
   covers, and lists every album with its track count and length. A large
   library takes about a minute. **Run it again whenever you change the music**,
   then make a new ISO.
4. Make the ISO and burn it, e.g. with [ImgBurn](https://www.imgburn.com) (use the
   download from ImgBurn's own site):
   * Choose *Create image file from files/folders*, and add **`BDMV` and
     `CERTIFICATE`** from the slot folder (not the slot folder itself).
   * On the Options tab, set File system to **UDF** and UDF revision to **2.50**.
     Build the ISO.
   * Choose *Write image file to disc*, at 2x–4x with Verify ticked. A BD-RE
     (rewritable) disc is handy for trying things out.

If you get *"'java' is not recognized"*, Java isn't on the PATH. Give the full path
instead, e.g. `"C:\Program Files\Java\jdk-17\bin\java.exe" -jar wvlibrary.jar WVPLAYER-01`.

## Player set-up

The disc keeps decoded music in the player's **BD-Live storage** (local storage).
That needs about 1 GB per hour of 44.1/48 kHz music, more for hi-res.

* **PlayStation 3:** nothing to set up. It uses the hard drive.
* **Oppo UDP-203 and similar standalone players:** the internal storage is small.
  Plug in a USB stick (16 GB or more is plenty). In the player's Setup menu, find
  *Persistent Storage* / *Storage Device*, choose the **USB drive**, and restart the
  player. Plugging the stick in isn't enough; it has to be selected there.
* **Panasonic (e.g. DMP-UB900):** these don't let a home-made disc use their
  storage, so the disc can't work on them. It shows a message saying so.

If the player shows nothing at all on an older TV or monitor, set the player's
24 Hz output option to Off. The disc asks for 1080i output.

## First insert

The first time a disc goes in, the menu shows *"Setting up the library (first
time only)"*. It copies the library information into the player's storage, then
asks the player to add it to the disc, which restarts the disc once. If the slot
held an older library, there's one extra restart first to release it. After that,
the album list appears straight away on every insert.

## Playing

| Remote | Action |
|---|---|
| UP / DOWN | move through albums / tracks |
| OK | open an album; play from the selected track (continues through the album) |
| POPUP MENU, red key, or the "Back to albums" row | back to the album list (music keeps playing) |
| LEFT / RIGHT | previous / next track while playing (LEFT restarts the current track if more than 3 s in) |
| STOP | stop playback |

* Choose a track and it starts after a couple of seconds, while the player decodes
  the start of it.
* While it plays, the rest of the album is decoded in the background. If decoding
  ever falls behind (for example right after you skip far ahead), the music pauses
  briefly and carries on.
* The album list shows how much of each album is decoded (**"45%"**), and
  **"ready"** once all of it is.
* After a minute without key presses, a large "now playing" view appears.

The menu at the bottom of the album list has:

* **Decode all albums in the background:** decodes everything not yet decoded,
  one album after another. It's good to leave running overnight. Playing always
  takes priority.
* **Remove library from this player:** deletes this disc's decoded music from the
  player's storage.
* **Show / Hide diagnostic messages:** see *If something goes wrong* below.

When storage fills up, the player releases the decoded copy of the album played
longest ago; it's decoded again next time you play it. If a single album is bigger
than the storage, decoding waits for playback and releases the parts already played.

## Cover art and track names

The library tool prepares the covers, so **any size and any JPEG/PNG works**,
including progressive JPEGs and 3000 × 3000 scans. For each album it takes the
cover picture in the folder (`cover`/`folder`/`front`/`album` `.jpg`/`.jpeg`/`.png`,
or the only picture there), else the first track's embedded cover. It shrinks it
and saves it as a standard JPEG on the disc. A track whose embedded cover differs
from the album's (compilations) gets its own. Your music folders and `.wv` files
are not changed. If the tool can't read a picture (rare, e.g. a CMYK JPEG), it says
so; re-save that picture from any image editor.

Track names come from the Title tag, else from the file name.

## Audio details

| Source | Plays as | Bit-exact? |
|---|---|---|
| 48 / 96 / 192 kHz, 24- or 32-bit | same rate, 24-bit LPCM | yes for 24-bit; 32-bit is reduced to 24 |
| 48 / 96 / 192 kHz, 16-bit | same rate, 16-bit LPCM | **yes** |
| 44.1 kHz (CD) | 48 kHz 24-bit (resampled) | no, but transparent: about 92–117 dB SNR in tests, flat to 20 kHz |
| 88.2 / 176.4 kHz | 96 / 192 kHz 24-bit (resampled) | no |
| mono | the same audio on both channels | yes (per channel) |

Consecutive tracks with the same format form one continuous stream, so track joins
are gapless. A change of format between tracks starts a new stream, and there may
be a brief pause at that point.

## If something goes wrong

Diagnostic messages (a log at the bottom and decoding-speed lines at the top right)
are hidden normally. To see them:

* choose **Show diagnostic messages** in the album menu (the player remembers it), or
* before making the ISO, edit `BDMV\JAR\00000\options.txt` in the disc folder and
  change `diagnostics=off` to `diagnostics=on`.

They also switch on by themselves when something goes wrong, so a photo of the
screen shows what happened. Messages that matter while listening (storage full,
decoding stopped, and so on) are shown for a few seconds either way.

* *"This disc has no music library yet"*: the library tool wasn't run (step 3).
* *"This player doesn't let this disc use its local storage"*: the player refuses
  BD-Live storage to home-made discs (e.g. Panasonic). It can't work on that player.
* An unreadable `.wv` file is listed greyed out and skipped. The library tool also
  lists it.
* After a power cut during decoding, the player finds any incomplete part of the
  decoded music when the disc next starts, and decodes it again.

For anything else, open an issue on GitHub with a photo of the screen (with
diagnostics on) and the player model.
