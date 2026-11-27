package wvplayer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.zip.CRC32;

import bdclip.ClipWriter;

/**
 * PC tool: lays out the music on a player disc for progressive playback.
 *
 *   java -jar wvlibrary.jar <disc folder>
 *
 * Reads the WavPack headers under <disc>/BDMV/JAR/00000/music, works out each
 * album's chunk clips, and writes into <disc>/BDMV/JAR/00000/vfs:
 *   lib/index.txt        album list + library id
 *   lib/aNNNNN.lay       layout of album (playlist) NNNNN
 *   lib/cNNNNN.bin       its clip info files + playlist, packed (copied to local storage on the player)
 *   bumf.xml, bumf.sf    the signed VFS manifest (clip info + playlists, chunk streams as progressive assets)
 * No audio is decoded here; the player decodes the WavPack files itself.
 */
public class MakeLibrary {

    static final int MAX_CLIP = 99998;          // 99999 is the silent clip

    public static void main(String[] a) throws Exception {
        if (a.length < 1) {
            System.out.println("usage: java -jar wvlibrary.jar <disc folder>   (the folder holding BDMV and CERTIFICATE)");
            System.exit(1);
        }
        File disc = new File(a[0]);
        File jarDir = new File(disc, "BDMV/JAR/00000");
        File music = new File(jarDir, "music");
        File vfs = new File(jarDir, "vfs");
        File lib = new File(vfs, "lib");
        File gopFile = new File(vfs, "black1080i.m2v");
        if (!music.isDirectory() || !gopFile.isFile()) {
            System.out.println("Not a player disc folder (expected BDMV/JAR/00000/music and vfs/black1080i.m2v under " + disc + ")");
            System.exit(1);
        }
        byte[] id = Files.readAllBytes(new File(disc, "CERTIFICATE/id.bdmv").toPath());
        long org = ((id[40] & 0xFFL) << 24) | ((id[41] & 0xFF) << 16) | ((id[42] & 0xFF) << 8) | (id[43] & 0xFF);
        StringBuilder dh = new StringBuilder();
        for (int i = 44; i < 60; i++) dh.append(String.format("%02x", id[i] & 0xFF));
        String discHex = dh.toString().replaceFirst("^0+(?=.)", "");
        String buda = Long.toHexString(org) + "/" + discHex + "/";

        byte[][] gop = ClipWriter.splitMpeg2(Files.readAllBytes(gopFile.toPath()));
        Catalog cat = new Catalog(music.getPath());
        if (cat.albums.length == 0) {
            System.out.println("No .wv files found under " + music);
            System.exit(1);
        }
        if (cat.truncated) System.out.println("Note: only the first " + Catalog.MAX_ALBUMS + " albums are used");

        if (lib.isDirectory()) {
            File[] old = lib.listFiles();
            for (File f : old) f.delete();
        }
        lib.mkdirs();

        File covers = new File(vfs, "covers");
        if (covers.isDirectory()) {
            for (File f : covers.listFiles()) f.delete();
        }
        covers.mkdirs();
        StringBuilder coverIndex = new StringBuilder();
        int[] coverStats = new int[2];

        long t0 = System.currentTimeMillis();
        int nextClip = 1;
        CRC32 crc = new CRC32();
        List<String> index = new ArrayList<>();
        List<Layout> layouts = new ArrayList<>();
        double totalSec = 0;
        for (Catalog.Album al : cat.albums) {
            Library L = new Library(al.dir, al.files, al.playlistId());
            L.scanHeaders();
            for (Library.Track t : L.tracks) {
                if (t.error != null) System.out.println("  skipping " + al.rel + "/" + t.name + ": " + t.error);
            }
            Layout lay = layout(L, al, nextClip);
            if (lay.groups.length == 0) {
                System.out.println("  " + al.rel + ": no readable tracks, skipped");
                continue;
            }
            int clips = lay.clipCount();
            if (nextClip + clips - 1 > MAX_CLIP) throw new Exception("too much music: more than " + MAX_CLIP + " clips");
            ByteArrayOutputStream blob = new ByteArrayOutputStream();
            DataOutputStream bo = new DataOutputStream(blob);
            ClipWriter.PlayItem[] items = dryRun(lay, gop, bo);
            byte[] mpl = playlist(lay, items);
            entry(bo, lay.playlistName() + ".mpl", mpl);
            bo.close();
            Files.write(new File(lib, "c" + lay.playlistName() + ".bin").toPath(), blob.toByteArray());
            StringWriter sw = new StringWriter();
            lay.write(sw);
            byte[] layBytes = sw.toString().getBytes(StandardCharsets.UTF_8);
            Files.write(new File(lib, "a" + lay.playlistName() + ".lay").toPath(), layBytes);
            crc.update(layBytes);
            crc.update(blob.toByteArray());
            prepareCovers(al, L, lay.playlistName(), covers, coverIndex, coverStats);
            index.add(lay.playlistId + "\t" + lay.firstClipOf() + "\t" + clips + "\t" + firstPlayable(lay) + "\t" + lay.rel);
            layouts.add(lay);
            nextClip += clips;
            double sec = 0;
            for (Library.Track t : L.tracks) if (t.error == null) sec += t.durationSec;
            totalSec += sec;
            System.out.printf("  %-60s %3d tracks %6.1f min %4d chunks%n", trim(al.label().replace('\u2014', '-'), 60), L.tracks.length, sec / 60, clips);
        }
        String libId = Long.toHexString(crc.getValue()) + "-" + (nextClip - 1);
        StringBuilder ix = new StringBuilder("wvlibrary-2\n" + libId + "\n" + index.size() + "\n");
        for (String s : index) ix.append(s).append('\n');
        Files.write(new File(lib, "index.txt").toPath(), ix.toString().getBytes(StandardCharsets.UTF_8));

        Files.write(new File(covers, "index.txt").toPath(), coverIndex.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(coverStats[0] + " cover pictures prepared (baseline JPEG, max " + COVER_SIDE + " px)"
                + (coverStats[1] > 0 ? ", " + coverStats[1] + " could not be read (see above)" : ""));

        byte[] manifest = manifest(org, id, buda, layouts);
        Files.write(new File(vfs, "bumf.xml").toPath(), manifest);
        Files.write(new File(vfs, "bumf.sf").toPath(), sign(manifest));
        // the same with the folder names in upper case, for players that spell them that way
        byte[] upper = manifest(org, id, buda.toUpperCase(), layouts);
        Files.write(new File(vfs, "bumfu.xml").toPath(), upper);
        Files.write(new File(vfs, "bumfu.sf").toPath(), sign(upper));
        System.out.printf("%d albums, %.1f hours, %d chunk clips, library id %s (%.1f s)%n",
                layouts.size(), totalSec / 3600, nextClip - 1, libId, (System.currentTimeMillis() - t0) / 1000.0);
        System.out.println("Done. Now make the ISO from " + disc);
    }

    static String firstPlayable(Layout lay) {
        for (Layout.Track t : lay.tracks) if (t.group >= 0) return t.name;
        return lay.tracks[0].name;
    }

    static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 2) + ".."; }

    /* ---------------------------------------------------------------- covers */

    static final int COVER_SIDE = 1200;

    /**
     * The PS3 shows only baseline JPEGs up to about 1600 px, so covers are converted here:
     * the album's cover (folder picture, else the first embedded cover) as NNNNN.jpg, and
     * each track whose embedded cover differs from it as NNNNN_<track>.jpg. Listed in covers/index.txt.
     */
    static void prepareCovers(Catalog.Album al, Library L, String pl, File dir, StringBuilder index, int[] stats) {
        Map<String, String> done = new HashMap<>();             // picture hash -> file written
        byte[] albumSrc = null;
        String albumWhat = null;
        File folder = folderCover(new File(al.dir));
        try {
            if (folder != null) {
                albumSrc = Files.readAllBytes(folder.toPath());
                albumWhat = folder.getName();
            }
        } catch (IOException e) { }
        byte[][] emb = new byte[L.tracks.length][];
        for (int i = 0; i < L.tracks.length; i++) {
            try {
                Tags t = Tags.read(L.tracks[i].path, true);
                if (t != null && t.cover != null && t.cover.length > 0) emb[i] = t.cover;
            } catch (Throwable e) { }
            if (albumSrc == null && emb[i] != null) {
                albumSrc = emb[i];
                albumWhat = L.tracks[i].name + " (embedded)";
            }
        }
        String albumHash = null;
        if (albumSrc != null) {
            albumHash = hash(albumSrc);
            String f = writeCover(albumSrc, pl + ".jpg", dir, albumWhat, al, stats);
            if (f != null) {
                done.put(albumHash, f);
                index.append(pl).append('\t').append(f).append('\n');
            }
        }
        for (int i = 0; i < emb.length; i++) {
            if (emb[i] == null) continue;
            String h = hash(emb[i]);
            if (h.equals(albumHash)) continue;                    // same as the album cover
            String f = done.get(h);
            if (f == null) {
                f = writeCover(emb[i], pl + "_" + i + ".jpg", dir, L.tracks[i].name + " (embedded)", al, stats);
                if (f == null) continue;
                done.put(h, f);
            }
            index.append(pl).append('_').append(i).append('\t').append(f).append('\n');
        }
    }

    static String writeCover(byte[] src, String name, File dir, String what, Catalog.Album al, int[] stats) {
        try {
            java.awt.image.BufferedImage in = javax.imageio.ImageIO.read(new ByteArrayInputStream(src));
            if (in == null) throw new IOException("unsupported picture format");
            int w = in.getWidth(), h = in.getHeight();
            double k = Math.min(1.0, COVER_SIDE / (double) Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * k)), nh = Math.max(1, (int) Math.round(h * k));
            java.awt.image.BufferedImage img = in;
            // halve in steps for a clean downscale, then the final bicubic step
            while (img.getWidth() / 2 >= nw && img.getHeight() / 2 >= nh) {
                img = scale(img, img.getWidth() / 2, img.getHeight() / 2);
            }
            img = scale(img, nw, nh);
            javax.imageio.ImageWriter wr = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
            javax.imageio.ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.9f);
            p.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DISABLED);   // baseline
            File out = new File(dir, name);
            try (javax.imageio.stream.ImageOutputStream os = javax.imageio.ImageIO.createImageOutputStream(out)) {
                wr.setOutput(os);
                wr.write(null, new javax.imageio.IIOImage(img, null, null), p);
            } finally {
                wr.dispose();
            }
            stats[0]++;
            return name;
        } catch (Throwable e) {
            stats[1]++;
            System.out.println("  cover not usable: " + al.rel + "/" + what + ": " + e.getMessage()
                    + " - re-save it as a standard JPEG or PNG");
            return null;
        }
    }

    static java.awt.image.BufferedImage scale(java.awt.image.BufferedImage src, int w, int h) {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Same rules as the player: cover/folder/front/album .jpg/.jpeg/.png, else the only picture. */
    static File folderCover(File dir) {
        String[] names = dir.list();
        if (names == null) return null;
        for (String pref : new String[] { "cover", "folder", "front", "album" }) {
            for (String n : names) {
                String l = n.toLowerCase();
                if (l.equals(pref + ".jpg") || l.equals(pref + ".jpeg") || l.equals(pref + ".png")) return new File(dir, n);
            }
        }
        File single = null;
        int count = 0;
        for (String n : names) {
            String l = n.toLowerCase();
            if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")) {
                count++;
                single = new File(dir, n);
            }
        }
        return count == 1 ? single : null;
    }

    static String hash(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(b);
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(String.format("%02x", x & 0xFF));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(Arrays.hashCode(b));
        }
    }

    /* ---------------------------------------------------------------- layout */

    static Layout layout(Library L, Catalog.Album al, int firstClip) {
        Layout lay = new Layout();
        lay.playlistId = al.playlistId();
        lay.rel = al.rel;
        lay.tracks = new Layout.Track[L.tracks.length];
        List<Layout.Group> gs = new ArrayList<>();
        int clip = firstClip;
        for (Library.Group lg : L.groups) {
            Layout.Group G = new Layout.Group();
            G.inRate = lg.inRate;
            G.outRate = lg.outRate;
            G.outBits = lg.outBits;
            G.firstTrack = lg.firstTrack;
            G.lastTrack = lg.lastTrack;
            G.firstClip = clip;
            long in = 0;
            List<Long> starts = new ArrayList<>();
            for (int i = lg.firstTrack; i <= lg.lastTrack; i++) {
                Library.Track t = L.tracks[i];
                if (t.group != lg.index) continue;
                starts.add(in * 30000L / ((long) G.inRate * 1001L * Layout.GRID) * Layout.GRID);
                in += t.samples;
            }
            G.inSamples = in;
            G.outSamples = (G.inRate == G.outRate) ? in : bdclip.Resampler.outputLength(in, G.inRate, G.outRate);
            long endFrames = G.outSamples * 30000L / ((long) G.outRate * 1001L);
            TreeSet<Long> cuts = new TreeSet<>();
            for (int s = 0; s < starts.size(); s++) {
                long st = starts.get(s);
                long next = (s + 1 < starts.size()) ? starts.get(s + 1) : endFrames;
                if (st > 0 && st <= endFrames - Layout.GRID) cuts.add(st);
                long pos = st;
                for (int k = 0; ; k++) {
                    pos += (long) Layout.LADDER[Math.min(k, Layout.LADDER.length - 1)] * Layout.GRID;
                    if (pos > next - Layout.GRID || pos > endFrames - Layout.GRID) break;
                    cuts.add(pos);
                }
            }
            G.cuts = new long[cuts.size()];
            int k = 0;
            for (long c : cuts) G.cuts[k++] = c;
            clip += G.chunks();
            gs.add(G);
        }
        lay.groups = gs.toArray(new Layout.Group[0]);
        for (int i = 0; i < L.tracks.length; i++) {
            Library.Track t = L.tracks[i];
            Layout.Track T = new Layout.Track();
            T.name = t.name;
            T.group = (t.error == null) ? t.group : -1;
            T.samples = t.samples;
            T.inRate = t.inRate;
            T.durationSec = t.durationSec;
            lay.tracks[i] = T;
        }
        for (int g = 0; g < lay.groups.length; g++) {
            Layout.Group G = lay.groups[g];
            long in = 0;
            for (int i = G.firstTrack; i <= G.lastTrack; i++) {
                Layout.Track T = lay.tracks[i];
                if (T.group != g) continue;
                T.inStart = in;
                T.mark45 = 45000L + in * 45000L / G.inRate;     // ClipWriter starts presenting at 1 s
                in += T.samples;
            }
        }
        return lay;
    }

    /** Runs the clip writer without audio: clip info + resume state of every chunk, and the play items. */
    static ClipWriter.PlayItem[] dryRun(Layout lay, byte[][] gop, DataOutputStream blob) throws IOException {
        List<ClipWriter.PlayItem> all = new ArrayList<>();
        int item = 0;
        for (Layout.Group G : lay.groups) {
            G.firstItem = item;
            int n = G.chunks();
            final byte[][] info = new byte[n][];
            ClipWriter.ChunkSink sink = new ClipWriter.ChunkSink() {
                public void chunkDone(int c, OutputStream os, byte[] ci) { info[c] = ci; }
                public OutputStream nextChunk(int c) { return OutputStream.nullOutputStream(); }
            };
            ClipWriter w = new ClipWriter(OutputStream.nullOutputStream(), G.outRate, 2, G.outBits, gop, 4, 4);
            if (G.cuts.length > 0) w.setChunking(G.cuts, sink);
            else w.setChunking(new long[0], sink);
            w.setTotalSamples(G.outSamples);
            int bpf = 2 * G.outBits / 8;
            byte[] zero = new byte[48000 * bpf];
            long left = G.outSamples;
            while (left > 0) {
                int k = (int) Math.min(48000, left);
                w.writePcm(zero, 0, k * bpf);
                left -= k;
            }
            w.finish();
            if (w.getChunkCount() != n) throw new IOException("chunk count " + w.getChunkCount() + " != " + n);
            String[] names = new String[n];
            for (int c = 0; c < n; c++) names[c] = Layout.five(G.clip(c));
            ClipWriter.PlayItem[] items = w.chunkItems(names);
            G.states = new long[n - 1][];
            G.in45 = new long[n];
            G.out45 = new long[n];
            for (int c = 0; c < n; c++) {
                if (c > 0) G.states[c - 1] = w.getCutState(c);
                G.in45[c] = items[c].in45;
                G.out45[c] = items[c].out45;
                entry(blob, names[c] + ".clp", info[c]);
                all.add(items[c]);
            }
            item += n;
        }
        // positions of the tracks in the whole playlist
        double base = 0;
        for (int g = 0; g < lay.groups.length; g++) {
            Layout.Group G = lay.groups[g];
            for (Layout.Track T : lay.tracks) {
                if (T.group == g) T.offsetSec = base + (T.mark45 - G.in45[0]) / 45000.0;
            }
            base += (G.out45[G.chunks() - 1] - G.in45[0]) / 45000.0;
        }
        return all.toArray(new ClipWriter.PlayItem[0]);
    }

    static byte[] playlist(Layout lay, ClipWriter.PlayItem[] items) {
        int[] conn = new int[items.length];
        for (Layout.Group G : lay.groups) {
            for (int c = 0; c < G.chunks(); c++) conn[G.firstItem + c] = (c == 0) ? 1 : 6;
        }
        List<Integer> mi = new ArrayList<>();
        List<Long> mt = new ArrayList<>();
        for (Layout.Track T : lay.tracks) {
            if (T.group < 0) continue;
            Layout.Group G = lay.groups[T.group];
            mi.add(G.firstItem + G.chunkAt(T.mark45));
            mt.add(T.mark45);
        }
        int[] markItem = new int[mi.size()];
        long[] mark45 = new long[mt.size()];
        for (int i = 0; i < markItem.length; i++) {
            markItem[i] = mi.get(i);
            mark45[i] = mt.get(i);
        }
        return ClipWriter.playlist(items, conn, markItem, mark45);
    }

    static void entry(DataOutputStream o, String name, byte[] data) throws IOException {
        o.writeUTF(name);
        o.writeInt(data.length);
        o.write(data);
    }

    /* ---------------------------------------------------------------- manifest */

    static byte[] manifest(long org, byte[] id, String buda, List<Layout> layouts) {
        StringBuilder disc = new StringBuilder();
        for (int i = 44; i < 60; i++) disc.append(String.format("%02x", id[i] & 0xFF));
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        x.append("<bumf:manifest xmlns:bumf=\"urn:BDA:bdmv;manifest\" ID=\"0x00000001\" orgID=\"0x")
         .append(String.format("%08x", org)).append("\" discID=\"0x").append(disc).append("\">\n");
        x.append("    <Assets>\n");
        for (Layout L : layouts) {
            for (Layout.Group G : L.groups) {
                for (int c = 0; c < G.chunks(); c++) {
                    String n = Layout.five(G.clip(c));
                    asset(x, "Asset", "BDMV/CLIPINF/" + n + ".clpi", buda + n + ".clp");
                }
            }
            asset(x, "Asset", "BDMV/PLAYLIST/" + L.playlistName() + ".mpls", buda + L.playlistName() + ".mpl");
        }
        x.append("        <Progressive>\n");
        for (Layout L : layouts) {
            for (Layout.Group G : L.groups) {
                for (int c = 0; c < G.chunks(); c++) {
                    String n = Layout.five(G.clip(c));
                    x.append("    ");
                    asset(x, "ProgressiveAsset", "BDMV/STREAM/" + n + ".m2ts", buda + n + ".m2t");
                }
            }
        }
        x.append("        </Progressive>\n");
        x.append("    </Assets>\n</bumf:manifest>\n");
        return x.toString().getBytes(StandardCharsets.UTF_8);
    }

    static void asset(StringBuilder x, String tag, String vp, String bu) {
        x.append("        <").append(tag).append(" VPFilename=\"").append(vp).append("\">\n");
        x.append("            <BUDAFile name=\"").append(bu).append("\"/>\n");
        x.append("        </").append(tag).append(">\n");
    }

    /** SHA1withRSA with the binding-unit key; the .sf file is the signature as a DER BIT STRING. */
    static byte[] sign(byte[] data) throws Exception {
        byte[] der;
        try (InputStream in = MakeLibrary.class.getResourceAsStream("/bu.key.der")) {
            if (in == null) throw new Exception("signing key missing from the tool");
            der = in.readAllBytes();
        }
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        Signature s = Signature.getInstance("SHA1withRSA");
        s.initSign(key);
        s.update(data);
        byte[] sig = s.sign();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x03);
        int len = sig.length + 1;
        if (len < 128) b.write(len);
        else if (len < 256) { b.write(0x81); b.write(len); }
        else { b.write(0x82); b.write(len >> 8); b.write(len & 0xFF); }
        b.write(0);
        b.write(sig);
        return b.toByteArray();
    }
}
