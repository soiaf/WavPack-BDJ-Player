/*
 * WavPack player for Blu-ray (BD-J) - albums in music/Artist/Album/ folders.
 *
 * The PC library tool lays every album out as a Blu-ray playlist of short
 * "chunk" clips and signs a VFS manifest in which the chunk streams are
 * progressive assets. On first insert the player copies the clip information
 * to local storage and applies that manifest once (one restart). After that,
 * choosing a track decodes the chunk that holds it (a second or two), enables
 * it, and starts playing while the decoder keeps writing ahead. Chunks stay in
 * local storage, so anything played once plays instantly afterwards.
 *
 * Remote:  UP/DOWN select, OK open/play, POPUP MENU / red / back: albums,
 *          LEFT/RIGHT previous/next track while playing, STOP stops.
 *
 * Java 1.3-level code.
 */
package wvplayer;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Vector;

import javax.media.ControllerEvent;
import javax.media.ControllerListener;
import javax.media.EndOfMediaEvent;
import javax.media.GainControl;
import javax.media.Manager;
import javax.media.MediaLocator;
import javax.media.Player;
import javax.media.Time;
import javax.tv.service.selection.ServiceContextFactory;
import javax.tv.xlet.Xlet;
import javax.tv.xlet.XletContext;
import javax.tv.xlet.XletStateChangeException;

import org.bluray.net.BDLocator;
import org.bluray.storage.DataAreaInfo;
import org.bluray.storage.StorageManager;
import org.bluray.ti.Title;
import org.bluray.ti.selection.TitleContext;
import org.bluray.vfs.VFSManager;
import org.dvb.event.EventManager;
import org.dvb.event.UserEvent;
import org.dvb.event.UserEventListener;
import org.dvb.event.UserEventRepository;
import org.havi.ui.HScene;
import org.havi.ui.HSceneFactory;

import bdclip.ClipWriter;

public class PlayerXlet implements Xlet, KeyListener, UserEventListener {

    static final String FONT = "RobotoMono";

    static final int S_STARTING = 0, S_ALBUMS = 1, S_TRACKS = 2, S_PREPARING = 3, S_ACTIVATING = 4, S_ERROR = 5;

    static final int K_POPUP = 461, K_RED = 403, K_STOP = 413;

    private XletContext ctx;
    private HScene scene;
    private Screen screen;
    private boolean dvbKeys = false;

    private String vfsRoot, budaRoot, orgHex, discHex;
    private File budaDir;
    private String libDir;                          // disc: BDMV/JAR/00000/vfs/lib
    private String libId;
    private Catalog catalog;
    private boolean[] enabled = new boolean[1];     // by clip number: chunk written and enabled
    private int[] albumDone = new int[0];           // enabled chunks per album
    private final Hashtable layouts = new Hashtable();
    private byte[][] gop;
    private final Hashtable covers = new Hashtable();  // prepared cover pictures: "NNNNN" / "NNNNN_track" -> path

    private volatile int state = S_STARTING;
    private volatile String headline = "Starting...";
    private volatile String detail = "";
    private volatile double progress = -1;
    private volatile boolean cancel = false;
    private final Vector log = new Vector();

    // album browser
    private int albumSel = 0;
    // album screen
    private Catalog.Album viewAlbum;
    private Library viewLib;
    private int trackSel = 0;

    // playback
    private Player player;
    private Library playLib;
    private Catalog.Album playAlbum;
    private volatile int playing = -1;
    private volatile int waitingFor = -1;          // track being decoded before it can start
    private volatile Library waitLib;              // ... and its album
    private volatile int playSeq = 0;
    private volatile double mediaBase = 0;
    private volatile boolean baseKnown = false;
    private volatile boolean ended = false;
    private volatile double position = 0;
    private volatile int restarts = 0;              // automatic restarts after an early end
    private volatile boolean noSeek = false;        // this player ends playback on jumps: use chapter starts
    private volatile boolean starved = false;       // the player said DataStarved and hasn't moved since
    private volatile long userJumpAt = 0;           // when we last started/jumped playback ourselves
    private volatile double decodeSpeed = 0;        // x real time, measured
    private volatile long chunkStartAt = 0;
    private volatile boolean slowWarned = false;

    // artwork + idle "now playing" view
    private Art art;
    private volatile long lastKey = System.currentTimeMillis();
    static final long IDLE_MS = 60000;

    // background decoding
    private final Object wlock = new Object();
    private Job pending;
    private volatile boolean writerStop = false;
    private volatile Catalog.Album writingAlbum;
    private volatile Catalog.Album failedAlbum;     // decoding this album threw: stop waiting for it
    private volatile int writerNextClip = -1;       // chunk the writer is producing now
    private volatile ChunkWriter currentWriter;
    private long lastFollow = 0;
    private volatile boolean decodeAll = false;
    private Thread writerThread;

    static final String A_BACK = "‹  Back to albums", A_ALL = "Decode all albums in the background",
                        A_ALL_STOP = "Stop decoding in the background", A_REMOVE = "Remove library from this player",
                        A_DIAG_ON = "Show diagnostic messages", A_DIAG_OFF = "Hide diagnostic messages";

    /* ================================================================ Xlet */

    public void initXlet(XletContext c) throws XletStateChangeException {
        ctx = c;
        vfsRoot = System.getProperty("bluray.vfs.root");
        budaRoot = System.getProperty("bluray.bindingunit.root");
        try {
            screen = new Screen();
            scene = HSceneFactory.getInstance().getDefaultHScene();
            scene.setLayout(null);
            screen.setBounds(0, 0, scene.getWidth() > 0 ? scene.getWidth() : 1920,
                             scene.getHeight() > 0 ? scene.getHeight() : 1080);
            scene.add(screen);
            scene.addKeyListener(this);
        } catch (Throwable t) {
            log("HScene: " + t);
        }
    }

    public void startXlet() throws XletStateChangeException {
        if (scene != null) {
            scene.setVisible(true);
            scene.requestFocus();
        }
        try {
            UserEventRepository r = new UserEventRepository("wvplayer-keys");
            r.addAllArrowKeys();
            r.addKey(KeyEvent.VK_ENTER);
            r.addKey(K_POPUP);
            r.addKey(K_RED);
            r.addKey(K_STOP);
            r.addKey(KeyEvent.VK_ESCAPE);
            r.addKey(KeyEvent.VK_BACK_SPACE);
            EventManager.getInstance().addUserEventListener(this, r);
            dvbKeys = true;
        } catch (Throwable t) {
            log("keys: " + t);
        }
        new Thread(new Runnable() {
            public void run() {
                startup();
            }
        }, "wv-startup").start();
    }

    public void pauseXlet() {}

    public void destroyXlet(boolean u) throws XletStateChangeException {
        cancel = true;
        writerStop = true;
        synchronized (wlock) { wlock.notifyAll(); }
        stopPlayer();
        closeIdle();
        if (dvbKeys) {
            try { EventManager.getInstance().removeUserEventListener(this); } catch (Throwable t) { }
        }
        if (scene != null) {
            scene.setVisible(false);
            HSceneFactory.getInstance().dispose(scene);
        }
    }

    /* ================================================================ startup flow */

    private void startup() {
        try {
            readIds();
            budaDir = findStorage();
            if (budaDir == null) return;
            loadDiag();
            art = new Art(screen, this);
            libDir = vfsRoot + "/BDMV/JAR/00000/vfs/lib";
            String[] ix = readLines(libDir + "/index.txt");
            if (ix == null || ix.length < 3 || !ix[0].equals("wvlibrary-2")) {
                fail("This disc has no music library yet: run wvlibrary.jar on the disc folder before making the ISO (see README)");
                return;
            }
            libId = ix[1];
            int n = Integer.parseInt(ix[2].trim());
            String[] lines = new String[n];
            System.arraycopy(ix, 3, lines, 0, n);
            catalog = new Catalog(vfsRoot + "/BDMV/JAR/00000/music", lines);
            String coverDir = vfsRoot + "/BDMV/JAR/00000/vfs/covers";
            String[] cv = readLines(coverDir + "/index.txt");
            for (int i = 0; cv != null && i < cv.length; i++) {
                int tab = cv[i].indexOf('\t');
                if (tab > 0) covers.put(cv[i].substring(0, tab), coverDir + "/" + cv[i].substring(tab + 1));
            }
            if (catalog.albums.length == 0) {
                fail("The library is empty");
                return;
            }
            Catalog.Album first = catalog.albums[0];
            boolean active = new File(vfsRoot + "/BDMV/PLAYLIST/" + Layout.five(first.playlistId()) + ".mpls").exists()
                             || new File(vfsRoot + "/BDMV/PLAYLIST/00001.mpls").exists();
            boolean setUp = libId.equals(readLine(new File(budaDir, "wvlib.txt")));
            boolean remove = new File(budaDir, "wvremove.txt").exists();

            if (remove) {
                if (active) {
                    activate(false, "Removing the library...");
                    return;
                }
                deleteLibraryFiles();
                new File(budaDir, "wvremove.txt").delete();
                fail("The library has been removed from this player. Eject the disc, or restart it to set it up again.");
                return;
            }
            if (!setUp) {
                if (active) {
                    activate(false, "New music on this disc - releasing the previous library...");
                    return;
                }
                setupLibrary();
                activate(true, "Adding the library to the disc (first time only)...");
                return;
            }
            if (!active) {
                activate(true, "Re-activating the library...");
                return;
            }
            setTries(0);
            loadEnabled();
            checkChunks();
            loadRecent();
            freeSpace();
            gop = ClipWriter.splitMpeg2(readAll(vfsRoot + "/BDMV/JAR/00000/vfs/black1080i.m2v"));
            writerThread = new Thread(new Runnable() {
                public void run() {
                    writerLoop();
                }
            }, "wv-writer");
            // normal priority: on the PS3 the lowest one cost 25% of the decoding speed while
            // playing (benchmark v2); on the Oppo it made no difference either way
            writerThread.setPriority(Thread.NORM_PRIORITY);
            writerThread.start();
            showAlbums();
            startClock();                                 // also clears messages before any song plays
            startIdle();                                  // silent clip: keeps the video running
        } catch (Throwable t) {
            fail("Startup: " + t + where(t));
        }
    }

    /**
     * This disc's folder in local storage (the binding unit data area). Players differ in
     * how they spell the organisation and disc IDs in the path, and some only allow the
     * exact spelling they use, so ask the player for its IDs and check permissions first.
     */
    private File findStorage() {
        if (budaRoot == null) {
            fail("This player has no local storage for discs (BD-Live storage). On some players it "
                 + "has to be enabled or a USB drive selected in the setup menu.");
            return null;
        }
        String apiOrg = null, apiDisc = null;
        try {
            Object o = ctx.getXletProperty("dvb.org.id");
            if (o != null) apiOrg = o.toString();
        } catch (Throwable t) { }
        try {
            apiDisc = org.bluray.ti.DiscManager.getDiscManager().getCurrentDisc().getId();
        } catch (Throwable t) { }
        log("storage root " + budaRoot + ", player says org " + apiOrg + " disc " + apiDisc);
        Vector c = new Vector();
        // the spelling that works on the PS3 first, then the player's own IDs
        String[] orgs = { orgHex, strip0x(apiOrg), pad8(orgHex) };
        String[] discs = { discHex, apiDisc, pad32(discHex) };
        for (int i = 0; i < orgs.length; i++) {
            for (int j = 0; j < discs.length; j++) {
                if (orgs[i] == null || discs[j] == null) continue;
                addOnce(c, orgs[i] + "/" + discs[j]);
                addOnce(c, orgs[i].toUpperCase() + "/" + discs[j].toUpperCase());
                addOnce(c, orgs[i].toLowerCase() + "/" + discs[j].toLowerCase());
            }
        }
        SecurityManager sm = System.getSecurityManager();
        StringBuffer tried = new StringBuffer();
        for (int i = 0; i < c.size(); i++) {
            String rel = (String) c.elementAt(i);
            String path = budaRoot + "/" + rel;
            try {
                if (sm != null) {
                    sm.checkPermission(new java.io.FilePermission(path + "/wvlib.txt", "read"));
                }
                if (i > 0) log("storage folder: " + rel + " (the spelling this player allows)");
                return new File(path);
            } catch (SecurityException e) {
                tried.append(i == 0 ? "" : ", ").append(rel);
            }
        }
        diagnoseStorage(sm);
        log("permission denied for " + tried);
        fail("This player doesn't let this disc use its local storage (BD-Live storage), which the "
             + "player needs to play WavPack music. Some players, such as Panasonic ones, only allow "
             + "that for commercial copy-protected discs, so this disc can't work on this player.");
        return null;
    }

    /** Why storage was refused: what the player grants this disc (for a photo of the screen). */
    private void diagnoseStorage(SecurityManager sm) {
        StringBuffer b = new StringBuffer("check:");
        try {
            Object[] signers = getClass().getSigners();
            b.append(" signed ").append(signers == null ? "NO" : String.valueOf(signers.length));
        } catch (Throwable t) { b.append(" signed ?"); }
        String[][] props = { { "bluray.profile.1", "p1" }, { "bluray.p1.version.major", "v" },
                             { "bluray.p1.version.minor", "." }, { "bluray.profile.2", " p2" } };
        for (int i = 0; i < props.length; i++) {
            try { b.append(i == 0 ? " " : "").append(props[i][1]).append(i == 0 || i == 3 ? "=" : "")
                   .append(System.getProperty(props[i][0])); } catch (Throwable t) { b.append("?"); }
        }
        // the other permissions the disc asks for: are any of them granted?
        b.append(" | prf: vfs ").append(granted(sm, 0)).append(" prefs ").append(granted(sm, 1))
         .append(" apps ").append(granted(sm, 2)).append(" select ").append(granted(sm, 3));
        String ada = null;
        try { ada = System.getProperty("dvb.persistent.root"); } catch (Throwable t) { }
        b.append(" | ada ").append(ada).append(" ")
         .append(canRead(sm, ada == null ? null : ada + "/" + orgHex + "/4000/x")).append(" ")
         .append(canRead(sm, ada == null ? null : ada + "/" + orgHex.toUpperCase() + "/4000/x"));
        b.append(" | root ").append(canRead(sm, budaRoot)).append(" ").append(canRead(sm, budaRoot + "/x"));
        b.append(" org ").append(canRead(sm, budaRoot + "/" + orgHex + "/x"));
        log(b.toString());
        // the folder above the storage root is named after a hash of a disc certificate: try the others
        String parent = budaRoot.substring(0, Math.max(0, budaRoot.lastIndexOf('/')));
        String here = budaRoot.substring(budaRoot.lastIndexOf('/') + 1);
        StringBuffer h = new StringBuffer("storage root " + here + ";");
        String[] certs = { "app.discroot.crt", "bu.discroot.crt" };
        for (int i = 0; i < certs.length; i++) {
            try {
                byte[] c = readAll(vfsRoot + "/CERTIFICATE/" + certs[i]);
                byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(c);
                String hx = "";
                for (int k = 0; k < 4; k++) hx += Integer.toHexString(0x100 | (d[k] & 0xff)).substring(1);
                hx = hx.toUpperCase();
                h.append(" ").append(certs[i]).append(" ").append(hx).append(" ")
                 .append(canRead(sm, parent + "/" + hx + "/" + orgHex + "/" + discHex + "/x"));
            } catch (Throwable t) {
                h.append(" ").append(certs[i]).append(" ").append(t.getClass().getName());
            }
        }
        log(h.toString());
    }

    private static String granted(SecurityManager sm, int which) {
        try {
            java.security.Permission p;
            if (which == 0) p = new org.bluray.vfs.VFSPermission("*");
            else if (which == 1) p = new org.dvb.user.UserPreferencePermission("read");
            else if (which == 2) p = new org.dvb.application.AppsControlPermission(null, null);
            else p = new javax.tv.service.selection.SelectPermission("*", "own");
            if (sm != null) sm.checkPermission(p);
            return "ok";
        } catch (SecurityException e) {
            return "no";
        } catch (Throwable t) {
            return "?";
        }
    }

        private static String canRead(SecurityManager sm, String path) {
        if (path == null) return "-";
        try {
            if (sm != null) sm.checkPermission(new java.io.FilePermission(path, "read"));
            return "ok";
        } catch (SecurityException e) {
            return "no";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void addOnce(Vector v, String s) {
        if (!v.contains(s)) v.addElement(s);
    }

    private static String strip0x(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        return s.length() > 0 ? s : null;
    }

    private static String pad8(String s) {
        if (s == null) return null;
        while (s.length() < 8) s = "0" + s;
        return s;
    }

    private static String pad32(String s) {
        if (s == null) return null;
        while (s.length() < 32) s = "0" + s;
        return s;
    }

    /** First insert: clip information + playlists into local storage (the audio is written later, on demand). */
    private void setupLibrary() throws Exception {
        state = S_PREPARING;
        headline = "Setting up the library (first time only)";
        progress = 0;
        repaint();
        if (!budaDir.isDirectory() && !budaDir.mkdirs()) throw new Exception("cannot create " + budaDir);
        deleteLibraryFiles();
        int total = 0;
        for (int i = 0; i < catalog.albums.length; i++) total += catalog.albums[i].clips;
        int done = 0;
        for (int i = 0; i < catalog.albums.length; i++) {
            Catalog.Album al = catalog.albums[i];
            detail = al.label();
            repaint();
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                    new FileInputStream(libDir + "/c" + Layout.five(al.playlistId()) + ".bin"), 65536));
            try {
                while (true) {
                    String name;
                    try {
                        name = in.readUTF();
                    } catch (java.io.EOFException e) {
                        break;
                    }
                    byte[] b = new byte[in.readInt()];
                    in.readFully(b);
                    writeFile(new File(budaDir, name), b);
                    if (name.endsWith(".clp")) done++;
                }
            } finally {
                in.close();
            }
            progress = done / (double) Math.max(1, total);
            repaint();
        }
        writeFile(new File(budaDir, "wvlib.txt"), (libId + "\n").getBytes());
        notice("Library set up: " + catalog.albums.length + " album" + (catalog.albums.length == 1 ? "" : "s"));
        log("library: " + total + " clips");
    }

    /** Which chunks are already written: the player lists the disabled ones. */
    private void loadEnabled() {
        int max = 0;
        for (int i = 0; i < catalog.albums.length; i++) {
            Catalog.Album a = catalog.albums[i];
            max = Math.max(max, a.firstClip + a.clips);
        }
        boolean[] en = new boolean[max + 1];
        for (int i = 0; i < en.length; i++) en[i] = true;
        try {
            String[] d = VFSManager.getInstance().getDisabledClipIDs();
            for (int i = 0; d != null && i < d.length; i++) {
                try {
                    int id = Integer.parseInt(d[i].trim());
                    if (id >= 0 && id < en.length) en[id] = false;
                } catch (NumberFormatException e) { }
            }
        } catch (Throwable t) {
            log("disabled clips: " + t);
        }
        int[] done = new int[catalog.albums.length];
        for (int i = 0; i < catalog.albums.length; i++) {
            Catalog.Album a = catalog.albums[i];
            for (int c = a.firstClip; c < a.firstClip + a.clips; c++) if (en[c]) done[i]++;
        }
        enabled = en;
        albumDone = done;
    }

    /**
     * Chunks are not forced to the storage one by one (that cost the Oppo most of its write
     * time), so after a power cut a chunk could be enabled but incomplete. Its exact size is
     * known: (packets at the next cut - packets at its own) x 192 bytes, and the last chunk
     * of a group is a whole number of 6144-byte units. A chunk that doesn't fit is disabled
     * and deleted, so it is decoded again.
     */
    private void checkChunks() {
        int bad = 0;
        for (int i = 0; i < catalog.albums.length; i++) {
            if (albumDone[i] == 0) continue;
            Catalog.Album al = catalog.albums[i];
            try {
                Layout L = layout(al);
                for (int g = 0; g < L.groups.length; g++) {
                    Layout.Group G = L.groups[g];
                    for (int c = 0; c < G.chunks(); c++) {
                        int clip = G.clip(c);
                        if (!isEnabled(clip)) continue;
                        long len = new File(budaDir, Layout.five(clip) + ".m2t").length();
                        long start = (c == 0) ? 0 : G.states[c - 1][1];
                        boolean ok = (c + 1 < G.chunks()) ? len == (G.states[c][1] - start) * 192
                                                          : len > 0 && len % 6144 == 0;
                        if (!ok) {
                            dropChunk(al, clip);
                            bad++;
                        }
                    }
                }
            } catch (Throwable t) {
                log("checking " + al.title + ": " + t);
            }
        }
        if (bad > 0) notice("Found " + bad + " damaged part" + (bad == 1 ? "" : "s") + " of decoded music (power cut?) - "
                            + (bad == 1 ? "it will" : "they will") + " be decoded again");
    }

    private void dropChunk(Catalog.Album a, int clip) {
        String path = budaDir.getPath() + "/" + Layout.five(clip) + ".m2t";
        try { VFSManager.getInstance().disableClip(path); } catch (Throwable t) { }
        new File(path).delete();
        enabled[clip] = false;
        if (a.index < albumDone.length && albumDone[a.index] > 0) albumDone[a.index]--;
    }

    private boolean isEnabled(int clip) {
        return clip >= 0 && clip < enabled.length && enabled[clip];
    }

    /** update = true: apply the manifest on the disc; false: release (undo) the update. Then restart. */
    private void activate(boolean update, String msg) {
        state = S_ACTIVATING;
        headline = msg;
        progress = -1;
        detail = "";
        repaint();
        try {
            int tries = getTries();
            if (update && tries >= 3) {
                setTries(0);
                fail("The player did not apply the update after 3 attempts");
                return;
            }
            VFSManager vfs = VFSManager.getInstance();
            if (!update) {
                vfs.requestUpdating(null, null, true);
            } else {
                setTries(tries + 1);
                requestUpdate(vfs);
            }
            detail = "The disc menu will restart in a moment.";
            repaint();
            Thread.sleep(2000);
            TitleContext tc = (TitleContext) ServiceContextFactory.getInstance().getServiceContext(ctx);
            tc.start((Title) tc.getService(), true);
        } catch (Throwable t) {
            fail("Update failed: " + t);
        }
    }

    /** As proven on the probe discs: manifest straight from the disc, else a renamed copy in storage. */
    private void requestUpdate(VFSManager vfs) throws Exception {
        String disc = vfsRoot + "/BDMV/JAR/00000/vfs/";
        // the manifest whose file names match the folder spelling this player uses
        String rel = budaDir.getPath().substring(budaRoot.length() + 1);
        String name = rel.equals(rel.toUpperCase()) && !rel.equals(rel.toLowerCase())
                      && new File(disc + "bumfu.xml").exists() ? "bumfu" : "bumf";
        try {
            vfs.requestUpdating(disc + name + ".xml", disc + name + ".sf", true);
            log("update requested (disc manifest " + name + ")");
            return;
        } catch (Throwable t) {
            log("disc manifest " + name + ": " + t);
        }
        copy(disc + name + ".xml", new File(budaDir, "wvman.xml"));
        copy(disc + name + ".sf", new File(budaDir, "wvman.sf"));
        vfs.requestUpdating(new File(budaDir, "wvman.xml").getPath(), new File(budaDir, "wvman.sf").getPath(), true);
        log("update requested (storage copy)");
    }

    private void deleteLibraryFiles() {
        String[] names = budaDir.list();
        if (names == null) return;
        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            if (n.endsWith(".m2t") || n.endsWith(".clp") || n.endsWith(".mpl") || n.startsWith("wv")) {
                new File(budaDir, n).delete();
            }
        }
    }

    private int getTries() {
        try {
            return Integer.parseInt(readLine(new File(budaDir, "wvtries.txt")).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private void setTries(int n) {
        try {
            writeFile(new File(budaDir, "wvtries.txt"), (n + "\n").getBytes());
        } catch (Throwable t) { }
    }

    private void fail(String msg) {
        state = S_ERROR;
        headline = "Problem";
        detail = msg;
        progress = -1;
        diag = true;                                      // show what led up to it
        System.out.println("[WvPlayer] " + msg);
        repaint();
    }

    /* ================================================================ albums */

    private Layout layout(Catalog.Album al) throws Exception {
        Layout L = (Layout) layouts.get(al);
        if (L == null) {
            InputStream in = new FileInputStream(libDir + "/a" + Layout.five(al.playlistId()) + ".lay");
            try {
                L = Layout.read(in);
            } finally {
                in.close();
            }
            layouts.put(al, L);
        }
        return L;
    }

    private Library library(Catalog.Album al, Layout L) {
        String key = "lib" + al.index;
        Library lib = (Library) layouts.get(key);
        if (lib == null) {
            lib = new Library(al.dir, L.names(), al.playlistId());
            L.apply(lib);
            layouts.put(key, lib);
            final Library fl = lib;
            new Thread(new Runnable() {
                public void run() {
                    fl.readTags();
                    repaint();
                }
            }, "wv-tags").start();
        }
        return lib;
    }

    /** The prepared cover of an album (from the library tool), else the original sources. */
    private String albumCoverKey(Catalog.Album a) {
        String f = (String) covers.get(Layout.five(a.playlistId()));
        return f != null ? f : Art.albumKey(a.dir, a.dir + "/" + a.files[0]);
    }

    /* ================================================================ storage */

    private volatile long freeCache = -2;               // -2 unknown yet, -1 the player doesn't say
    private final Hashtable recent = new Hashtable();  // album playlist -> last played (ms), oldest evicted first
    static final long MARGIN = 30L << 20;

    /** Free bytes in this disc's local storage (binding unit data area), or -1 if the player doesn't say. */
    private long freeSpace() {
        try {
            DataAreaInfo d = StorageManager.getInstance().getBindingunitDataAreaInfo();
            long f = d.getFreeSpace();
            freeCache = f;
            return f;
        } catch (Throwable t) {
            freeCache = -1;
            return -1;
        }
    }

    private void loadRecent() {
        String[] l = readLines(new File(budaDir, "wvrecent.txt").getPath());
        for (int i = 0; l != null && i < l.length; i++) {
            String[] f = Layout.words(l[i]);
            if (f.length == 2) {
                try { recent.put(f[0], new Long(Long.parseLong(f[1]))); } catch (NumberFormatException e) { }
            }
        }
    }

    private void touchRecent(Catalog.Album al) {
        recent.put(String.valueOf(al.playlistId()), new Long(System.currentTimeMillis()));
        StringBuffer b = new StringBuffer();
        java.util.Enumeration e = recent.keys();
        while (e.hasMoreElements()) {
            Object k = e.nextElement();
            b.append(k).append(' ').append(recent.get(k)).append('\n');
        }
        try { writeFile(new File(budaDir, "wvrecent.txt"), b.toString().getBytes()); } catch (Throwable t) { }
    }

    private boolean releaseRefused = false;

    /**
     * Makes room for a chunk: if storage is short, releases the decoded chunks of the
     * least recently played other albums (they decode again when next played).
     */
    private void makeRoom(Catalog.Album writing, long bytes) throws IOException {
        long free = freeSpace();
        if (free < 0 || free >= bytes + MARGIN) return;
        Catalog.Album listening = (player != null && waitingFor < 0) ? playAlbum : null;
        Catalog.Album current = (player != null) ? playAlbum : null;      // may be waiting on a new track
        while (free < bytes + MARGIN && !releaseRefused) {
            Catalog.Album victim = null;
            long oldest = Long.MAX_VALUE;
            for (int i = 0; i < catalog.albums.length; i++) {
                Catalog.Album a = catalog.albums[i];
                if (a == writing || a == listening || albumDone[i] == 0) continue;
                Long t = (Long) recent.get(String.valueOf(a.playlistId()));
                long tv = (t != null) ? t.longValue() : 0;
                if (tv < oldest) {
                    oldest = tv;
                    victim = a;
                }
            }
            if (victim == null) {
                // only the album being played is left: release what has been played,
                // and if decoding is far enough ahead, wait for playback to catch up
                if (current == null || current != writing) break;
                boolean told = false;
                while (free < bytes + MARGIN) {
                    if (writerStop || cancel) throw new IOException("stopped");
                    int pc = positionClip(current);
                    if (pc > current.firstClip + 1) {
                        int freed = release(current, pc - 1);
                        if (freed > 0) log("Storage full: released " + freed + " already played chunk" + (freed == 1 ? "" : "s"));
                    }
                    free = freeSpace();
                    if (free >= bytes + MARGIN) break;
                    if (!told) {
                        notice("Storage full: decoding waits for playback to catch up");
                        told = true;
                    }
                    try { Thread.sleep(2000); } catch (InterruptedException e) { }
                    if (player == null || playAlbum != current) break;
                }
                break;
            }
            int freed = release(victim, Integer.MAX_VALUE);
            if (freed == 0) {
                releaseRefused = true;
                notice("Storage is full and the player won't free any space: decoding has to wait");
                break;
            }
            log("Storage full: released " + freed + " decoded chunk" + (freed == 1 ? "" : "s") + " of " + victim.label());
            free = freeSpace();
        }
        if (free < bytes + MARGIN) {
            throw new IOException("the player's storage is full (" + (free >> 20) + " MB free) - see README: "
                                  + "use a larger USB drive as the player's BD-Live storage, or Remove library");
        }
    }

    /** Clip at the current playback position of album al, or -1. */
    private int positionClip(Catalog.Album al) {
        Layout L = (Layout) layouts.get(al);
        if (L == null) return -1;
        double base = 0;
        for (int g = 0; g < L.groups.length; g++) {
            Layout.Group G = L.groups[g];
            double dur = (G.out45[G.chunks() - 1] - G.in45[0]) / 45000.0;
            if (position < base + dur || g == L.groups.length - 1) {
                return G.clip(G.chunkAt(G.in45[0] + (long) (Math.max(0, position - base) * 45000)));
            }
            base += dur;
        }
        return -1;
    }

    /** Disables and deletes an album's decoded chunks below clip 'before'; returns how many. */
    private int release(Catalog.Album a, int before) {
        VFSManager vfs = VFSManager.getInstance();
        int n = 0;
        for (int c = a.firstClip; c < a.firstClip + a.clips && c < before; c++) {
            if (!isEnabled(c)) continue;
            String path = budaDir.getPath() + "/" + Layout.five(c) + ".m2t";
            try {
                if (!vfs.disableClip(path)) {
                    if (n == 0) return 0;
                    continue;
                }
            } catch (Throwable t) {
                if (n == 0) return 0;
                continue;
            }
            new File(path).delete();
            enabled[c] = false;
            if (a.index < albumDone.length && albumDone[a.index] > 0) albumDone[a.index]--;
            n++;
        }
        repaint();
        return n;
    }

    /** " at Class.method:line < ..." - the first few stack frames, for error reports. */
    static String where(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String[] lines = Catalog.split(sw.toString(), '\n');
            StringBuffer b = new StringBuffer();
            int n = 0;
            int sys = 0, own = 0;
            for (int i = 0; i < lines.length && own < 2; i++) {
                String l = lines[i].trim();
                if (!l.startsWith("at ")) continue;
                l = l.substring(3);
                boolean mine = l.startsWith("wvplayer.") || l.startsWith("bdclip.") || l.startsWith("com.beatofthedrum.");
                if (mine) own++;
                else if (sys++ >= 2) continue;
                int paren = l.indexOf('(');
                String m = paren > 0 ? l.substring(0, paren) : l;
                int dot = m.lastIndexOf('.');
                int dot2 = dot > 0 ? m.lastIndexOf('.', dot - 1) : -1;
                if (dot2 >= 0) m = m.substring(dot2 + 1);
                String line = "";
                int colon = l.lastIndexOf(':');
                if (paren > 0 && colon > paren) line = l.substring(colon, l.length() - 1);
                b.append(n == 0 ? " at " : " < ").append(m).append(line);
                n++;
            }
            return b.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /** "ready", "45%" or "" for an album. */
    private String albumStatus(int i) {
        Catalog.Album a = catalog.albums[i];
        if (i >= albumDone.length || a.clips == 0) return "";
        int d = albumDone[i];
        if (d >= a.clips) return "ready";
        if (d == 0) return writingAlbum == a ? "0%" : "";
        return (d * 100 / a.clips) + "%";
    }

    private int readyCount() {
        int n = 0;
        for (int i = 0; i < albumDone.length; i++) if (albumDone[i] >= catalog.albums[i].clips) n++;
        return n;
    }

    /* ================================================================ background decoding */

    static final class Job {
        Catalog.Album al;
        int group, chunk;
        Job(Catalog.Album al, int group, int chunk) { this.al = al; this.group = group; this.chunk = chunk; }
    }

    /** Decode this album from (group, chunk) on, interrupting whatever is being decoded now. */
    private void want(Job j) {
        synchronized (wlock) {
            pending = j;
            writerStop = true;
            wlock.notifyAll();
        }
    }

    private void writerLoop() {
        while (!cancel) {
            Job j;
            synchronized (wlock) {
                while (pending == null && !cancel) {
                    if (decodeAll) {
                        Catalog.Album next = nextUndecoded();
                        if (next != null) {
                            pending = new Job(next, 0, 0);
                            break;
                        }
                        decodeAll = false;
                        notice("All albums are decoded");
                        repaint();
                    }
                    try { wlock.wait(); } catch (InterruptedException e) { }
                }
                j = pending;
                pending = null;
                writerStop = false;
            }
            if (j == null) continue;
            int tries = 0;
            while (true) {
                try {
                    writeAlbum(j);
                    break;
                } catch (Throwable t) {
                    if (writerStop || cancel) break;                // interrupted on purpose
                    String stage = (currentWriter != null) ? currentWriter.stage : "?";
                    long fs = freeSpace();
                    problem("Decoding " + j.al.title + " failed while " + stage + ": " + t + where(t)
                        + (fs >= 0 ? " (storage: " + (fs >> 20) + " MB free)" : ""));
                    if (++tries <= 2 && !writerStop && !cancel) {
                        log("retrying (" + tries + ")");
                        try { Thread.sleep(1500); } catch (InterruptedException e) { }
                        continue;
                    }
                    failedAlbum = j.al;
                    break;
                }
            }
            if (failedAlbum == j.al) {
                if (decodeAll) {
                    decodeAll = false;
                    log("Background decoding stopped");
                }
            }
            writingAlbum = null;
            writerNextClip = -1;
            repaint();
        }
    }

    private Catalog.Album nextUndecoded() {
        for (int i = 0; i < catalog.albums.length; i++) {
            if (albumDone[i] < catalog.albums[i].clips) return catalog.albums[i];
        }
        return null;
    }

    /** Writes every chunk of the album that is not written yet, from the job's position to the end. */
    private void writeAlbum(final Job j) throws Exception {
        final Catalog.Album al = j.al;
        Layout L = layout(al);
        writingAlbum = al;
        ChunkWriter w = new ChunkWriter(L, al.dir, budaDir, gop, new ChunkWriter.Host() {
            public boolean isEnabled(int clip) { return PlayerXlet.this.isEnabled(clip); }
            public void chunkWritten(int clip) throws IOException {
                enable(al, clip);
                ChunkWriter cw = currentWriter;
                String r = (cw != null) ? cw.speedReport() : null;
                if (r != null) speedLine = r;
            }
            public boolean stopRequested() { return writerStop || cancel; }
            public void decoded(long s) { }
            public void needSpace(long bytes) throws IOException { makeRoom(al, bytes); }
        });
        currentWriter = w;
        int g = j.group, c = j.chunk;
        while (g < L.groups.length && !writerStop && !cancel) {
            Layout.Group G = L.groups[g];
            while (c < G.chunks() && isEnabled(G.clip(c))) c++;
            if (c >= G.chunks()) {
                g++;
                c = 0;
                continue;
            }
            writerNextClip = G.clip(c);
            chunkStartAt = System.currentTimeMillis();
            int n = w.write(g, c);
            c += Math.max(1, n);
        }
    }

    private void enable(Catalog.Album al, int clip) throws IOException {
        String path = budaDir.getPath() + "/" + Layout.five(clip) + ".m2t";
        VFSManager vfs = VFSManager.getInstance();
        String why = "returned false";
        boolean ok = false;
        for (int attempt = 0; attempt < 6 && !ok; attempt++) {
            if (attempt == 3) {
                // some players may refuse while playback waits on a missing chunk: stop, then retry
                if (restartAround(al)) log("enableClip(" + clip + ") refused - retrying with playback stopped");
            }
            if (attempt > 0) {
                try { Thread.sleep(400); } catch (InterruptedException e) { }
            }
            try {
                ok = vfs.enableClip(path) || vfs.isEnabledClip(String.valueOf(clip));
            } catch (Throwable t) {
                why = t.toString();
            }
        }
        if (!ok) {
            int left = -1;
            try { left = vfs.getDisabledClipIDs().length; } catch (Throwable t) { }
            throw new IOException("the player refused to enable chunk " + clip + " (" + why + ", "
                                  + left + " not ready)");
        }
        writerNextClip = clip + 1;
        measureSpeed(al, clip);
        if (clip < enabled.length && !enabled[clip]) {
            enabled[clip] = true;
            if (al.index < albumDone.length) albumDone[al.index]++;
        }
        resumeAfterRestart();
        repaint();
    }

    /** Decode speed from the time this chunk took (includes seeking in and writing). */
    /** Where the background decoder's time goes (shown above the log). */
    private volatile String speedLine;

    private void measureSpeed(Catalog.Album al, int clip) {
        long now = System.currentTimeMillis();
        long ms = now - chunkStartAt;
        chunkStartAt = now;
        try {
            Layout L = layout(al);
            for (int g = 0; g < L.groups.length; g++) {
                Layout.Group G = L.groups[g];
                if (clip >= G.firstClip && clip < G.firstClip + G.chunks()) {
                    int c = clip - G.firstClip;
                    double sec = (G.out45[c] - G.in45[c]) / 45000.0;
                    if (ms > 200 && sec > 1) {
                        double v = sec * 1000.0 / ms;
                        boolean first = decodeSpeed == 0;
                        decodeSpeed = first ? v : decodeSpeed * 0.7 + v * 0.3;
                        if (first) log("decoding at " + secs(v) + "x real time");
                        if (decodeSpeed < 1.2 && !slowWarned) {
                            slowWarned = true;
                            notice("This player decodes slower than real time: playback will pause now and then. "
                                + "'Decode all albums in the background' first for smooth playback.");
                        }
                    }
                    return;
                }
            }
        } catch (Throwable t) { }
    }

    private volatile Library resumeLib;
    private volatile Catalog.Album resumeAlbum;
    private volatile int resumeTrack = -1;
    private volatile double resumePos;

    /** Stops playback of this album (to be resumed once the chunk is enabled). */
    private boolean restartAround(Catalog.Album al) {
        if (player == null || playAlbum != al || playing < 0) return false;
        resumeLib = playLib;
        resumeAlbum = playAlbum;
        resumeTrack = playing;
        resumePos = position;
        if (!noIdle && !noSwitch) goIdle(player); else stopPlayer();
        if (player != null) stopPlayer();                 // the switch didn't happen
        return true;
    }

    private void resumeAfterRestart() {
        if (resumeTrack < 0) return;
        final Library lib = resumeLib;
        final Catalog.Album al = resumeAlbum;
        final int t = resumeTrack;
        final double pos = resumePos;
        resumeTrack = -1;
        final int seq = ++playSeq;
        background(new Runnable() {
            public void run() {
                if (seq != playSeq) return;
                playFresh(lib, al, t);
                try {
                    double off = lib.tracks[t].offsetSec;
                    if (player != null && pos - off > 2 && !noSeek) player.setMediaTime(new Time(mediaBase + pos));
                } catch (Throwable e) { }
                log("playback resumed");
            }
        });
    }

    /**
     * Called by the clock: if playback has moved somewhere the decoder isn't
     * heading (the player's own NEXT/PREV or chapter keys), move the decoder there.
     */
    private void followPosition(double pos) {
        Catalog.Album al = playAlbum;
        if (al == null || failedAlbum == al) return;
        Layout L = (Layout) layouts.get(al);
        if (L == null || L.groups.length == 0) return;
        double base = 0;
        int g = 0;
        long t45 = 0;
        for (; g < L.groups.length; g++) {
            Layout.Group G = L.groups[g];
            double dur = (G.out45[G.chunks() - 1] - G.in45[0]) / 45000.0;
            if (pos < base + dur || g == L.groups.length - 1) {
                t45 = G.in45[0] + (long) (Math.max(0, pos - base) * 45000);
                break;
            }
            base += dur;
        }
        Layout.Group G = L.groups[g];
        int c = G.chunkAt(t45);
        int target = -1;
        for (int k = c; k < G.chunks() && k <= c + 1; k++) {
            if (!isEnabled(G.clip(k))) {
                target = k;
                break;
            }
        }
        if (target < 0) return;                                 // this chunk and the next are ready
        if (target == c + 1 && G.in45[c + 1] - t45 > 30L * 45000) return;   // plenty of time
        if (writingAlbum == al && writerNextClip == G.clip(target)) return; // already on it
        long now = System.currentTimeMillis();
        if (now - lastFollow < 3000) return;
        lastFollow = now;
        want(new Job(al, g, target));
    }

    /* ================================================================ screens */

    private void showAlbums() {
        state = S_ALBUMS;
        progress = -1;
        headline = "WavPack player";
        repaint();
    }

    private void openAlbum(int ai) {
        Catalog.Album al = catalog.albums[ai];
        try {
            Layout L = layout(al);
            viewLib = library(al, L);
        } catch (Throwable t) {
            problem("Album " + al.title + ": " + t);
            return;
        }
        viewAlbum = al;
        trackSel = 1;
        if (playAlbum == al && playing >= 0) trackSel = rowOfTrack(playing);
        state = S_TRACKS;
        repaint();
    }

    /** Rows of the album screen: Back, tracks... */
    private int trackRows() { return 1 + viewLib.tracks.length; }

    private int firstTrackRow() { return 1; }

    private int rowOfTrack(int t) { return firstTrackRow() + t; }

    private String trackScreenAction(int row) {
        return (row == 0) ? A_BACK : null;
    }

    private String[] albumActions() {
        boolean all = readyCount() >= catalog.albums.length;
        String d = diag ? A_DIAG_OFF : A_DIAG_ON;
        if (all) return new String[] { A_REMOVE, d };
        return new String[] { decodeAll ? A_ALL_STOP : A_ALL, A_REMOVE, d };
    }

    /* ================================================================ keys */

    public void userEventReceived(UserEvent e) {
        if (e.getType() == KeyEvent.KEY_PRESSED) handleKey(e.getCode());
    }

    public void keyPressed(KeyEvent e) {
        if (!dvbKeys) handleKey(e.getKeyCode());
    }

    public void keyReleased(KeyEvent e) {}
    public void keyTyped(KeyEvent e) {}

    private synchronized void handleKey(int k) {
        boolean wasIdle = idleView();
        lastKey = System.currentTimeMillis();
        if (wasIdle) {
            repaint();
            return;
        }
        if (k == K_STOP) {
            playSeq++;
            waitingFor = -1;
            final Player sp = player;
            if (sp != null && !noIdle && !noSwitch) background(new Runnable() { public void run() { goIdle(sp); } });
            else stopPlayer();
        } else if ((k == KeyEvent.VK_LEFT || k == KeyEvent.VK_RIGHT) && playing >= 0 && playLib != null) {
            final int target = (k == KeyEvent.VK_RIGHT) ? nextPlayable(playing + 1)
                    : prevPlayable(playing - (position - playLib.tracks[playing].offsetSec > 3 ? 0 : 1));
            if (target >= 0) {
                if (state == S_TRACKS && viewLib == playLib) trackSel = rowOfTrack(target);
                final Library lib = playLib;
                final Catalog.Album al = playAlbum;
                final int seq = ++playSeq;
                background(new Runnable() { public void run() { play(lib, al, target, seq); } });
            }
        } else if (state == S_ALBUMS) {
            int n = catalog.albums.length + albumActions().length;
            if (k == KeyEvent.VK_UP) albumSel = (albumSel + n - 1) % n;
            else if (k == KeyEvent.VK_DOWN) albumSel = (albumSel + 1) % n;
            else if (k == KeyEvent.VK_ENTER) {
                if (albumSel < catalog.albums.length) {
                    openAlbum(albumSel);
                } else {
                    final String a = albumActions()[albumSel - catalog.albums.length];
                    background(new Runnable() { public void run() { albumAction(a); } });
                }
            }
        } else if (state == S_TRACKS) {
            int n = trackRows();
            if (k == KeyEvent.VK_UP) trackSel = (trackSel + n - 1) % n;
            else if (k == KeyEvent.VK_DOWN) trackSel = (trackSel + 1) % n;
            else if (k == K_POPUP || k == K_RED || k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_BACK_SPACE) showAlbums();
            else if (k == KeyEvent.VK_ENTER) {
                if (trackScreenAction(trackSel) == A_BACK) {
                    showAlbums();
                } else {
                    final int t = trackSel - firstTrackRow();
                    final Library lib = viewLib;
                    final Catalog.Album al = viewAlbum;
                    if (t >= 0 && t < lib.tracks.length && lib.tracks[t].group >= 0) {
                        final int seq = ++playSeq;
                        background(new Runnable() { public void run() { play(lib, al, t, seq); } });
                    }
                }
            }
        } else if (state == S_ERROR && k == KeyEvent.VK_ENTER && catalog != null && albumDone.length > 0) {
            showAlbums();
        }
        repaint();
    }

    private void albumAction(String a) {
        if (a == A_DIAG_ON || a == A_DIAG_OFF) {
            setDiag(a == A_DIAG_ON);
        } else if (a == A_ALL) {
            decodeAll = true;
            notice("Decoding all albums in the background (playing a track takes priority)");
            synchronized (wlock) { wlock.notifyAll(); }
        } else if (a == A_ALL_STOP) {
            decodeAll = false;
            if (writingAlbum != null && (playAlbum == null || writingAlbum != playAlbum)) {
                synchronized (wlock) { writerStop = true; }
            }
            notice("Background decoding stopped");
        } else if (a == A_REMOVE) {
            playSeq++;
            stopPlayer();
            closeIdle();
            decodeAll = false;
            synchronized (wlock) { writerStop = true; }
            try {
                if (!budaDir.isDirectory()) budaDir.mkdirs();
                writeFile(new File(budaDir, "wvremove.txt"), "1\n".getBytes());
                activate(false, "Removing the library...");
            } catch (Throwable t) {
                fail("Remove: " + t);
            }
        }
        repaint();
    }

    private static void background(Runnable r) {
        new Thread(r, "wv-action").start();
    }

    private int nextPlayable(int i) {
        while (i < playLib.tracks.length && playLib.tracks[i].group < 0) i++;
        return i < playLib.tracks.length ? i : -1;
    }

    private int prevPlayable(int i) {
        if (i < 0) i = 0;
        while (i >= 0 && playLib.tracks[i].group < 0) i--;
        return i;
    }

    /* ================================================================ playback */

    /**
     * Plays a track: makes sure the decoder is working from the chunk that holds
     * its start, waits until that chunk is enabled, then plays (or jumps).
     */
    private void play(Library lib, Catalog.Album al, int track, int seq) {
        Layout L;
        try {
            L = layout(al);
        } catch (Throwable t) {
            problem("play: " + t);
            return;
        }
        Layout.Track T = L.tracks[track];
        Layout.Group G = L.groups[T.group];
        int c = G.chunkAt(T.mark45);
        touchRecent(al);
        boolean ready = isEnabled(G.clip(c)) && (c + 1 >= G.chunks() || isEnabled(G.clip(c + 1)));
        if (!ready || writingAlbum != al) {
            if (failedAlbum == al) failedAlbum = null;
            want(new Job(al, T.group, c));
        }
        boolean waited = false;
        if (!isEnabled(G.clip(c))) {
            waited = true;
            waitLib = lib;
            waitingFor = track;
            repaint();
            long end = System.currentTimeMillis() + 120000;
            while (!isEnabled(G.clip(c)) && seq == playSeq && !cancel && failedAlbum != al
                   && System.currentTimeMillis() < end) {
                try { Thread.sleep(100); } catch (InterruptedException e) { }
            }
            if (seq == playSeq) waitingFor = -1;
            if (!isEnabled(G.clip(c))) {
                if (seq == playSeq && failedAlbum != al) notice("Could not decode " + lib.tracks[track].display() + " in time");
                repaint();
                return;
            }
        }
        // a slow decoder needs a head start: also wait for the next chunk
        if (decodeSpeed > 0 && decodeSpeed < 2.5 && c + 1 < G.chunks() && !isEnabled(G.clip(c + 1))) {
            waited = true;
            waitLib = lib;
            waitingFor = track;
            repaint();
            long end2 = System.currentTimeMillis() + 120000;
            while (!isEnabled(G.clip(c + 1)) && seq == playSeq && !cancel && failedAlbum != al
                   && System.currentTimeMillis() < end2) {
                try { Thread.sleep(100); } catch (InterruptedException e) { }
            }
            if (seq == playSeq) waitingFor = -1;
        }
        if (seq != playSeq) return;
        synchronized (this) {
            if (seq != playSeq) return;
            // jumping within the playing playlist into freshly decoded chunks made some
            // players (Oppo UDP-203) end playback: start a fresh player at the chapter then
            if (!waited && player != null && baseKnown && !ended && lib == playLib && playing >= 0) {
                if (jump(track)) return;
            }
            restarts = 0;
            playFresh(lib, al, track);
        }
    }

    private volatile boolean noSwitch = false;        // this player misbehaves after selectPlayList
    private volatile long switchedAt = 0;            // when the last start was a playlist switch

    /** All events of a player we made (music, or the silent clip while idle). */
    private void listen(final Player fp) {
        fp.addControllerListener(new ControllerListener() {
            public void controllerUpdate(ControllerEvent e) {
                if (fp == idle) {                               // the silent clip between songs
                    if (e instanceof EndOfMediaEvent) background(new Runnable() { public void run() { loopIdle(fp); } });
                    return;
                }
                String n = e.getClass().getName();
                n = n.substring(n.lastIndexOf('.') + 1);
                if (n.endsWith("Event")) n = n.substring(0, n.length() - 5);
                if (n.equals("DataStarved") && player == fp) starved = true;
                if (!n.equals("Transition") && !n.equals("MediaTimeSet") && !n.equals("Realize")
                        && !n.equals("Prefetch") && !n.equals("Start") && !n.equals("RateChange")
                        && !n.equals(lastEvent) && player == fp) {
                    lastEvent = n;
                    log("player event: " + n + " at " + mmss(position));
                }
                if (e instanceof EndOfMediaEvent) {
                    if (player != fp) return;                   // an old player
                    double end = playLib != null ? playLib.totalSeconds() : 0;
                    double at = position;
                    int t = playing;
                    if (end > 0 && at < end - 5 && t >= 0 && restarts < 3) {
                        restarts++;
                        if (switchedAt > 0 && !noSwitch) {
                            noSwitch = true;                    // then use a new player each time
                            log("this player stops after a playlist switch - using a new player for each start");
                        }
                        if (!noSeek) log("this player can't jump within a playlist - using chapter starts");
                        noSeek = true;
                        log("playback stopped early at " + mmss(at) + " - restarting");
                        resumeLib = playLib;
                        resumeAlbum = playAlbum;
                        resumeTrack = t;
                        resumePos = at;
                        background(new Runnable() { public void run() { resumeAfterRestart(); } });
                        return;
                    }
                    ended = true;
                    playing = -1;
                    repaint();
                    background(new Runnable() { public void run() { goIdle(fp); } });
                }
            }
        });
    }

    /* ---------------------------------------------------------------- silent clip */

    /*
     * Between songs the player keeps running a short silent black clip (playlist 00999 on
     * the disc), so the video never stops: making a new player after the video had stopped
     * gave a few seconds of black screen. Starting a song switches this player to it.
     */
    static final String IDLE_LOC = "bd://PLAYLIST:00999";    // clip 99999; playlist ids stop at 01999
    private volatile Player idle;
    private volatile boolean noIdle = false;

    /** At startup, once the album list is up. */
    private void startIdle() {
        if (idle != null || player != null || noIdle || noSwitch) return;
        try {
            Player p = Manager.createPlayer(new MediaLocator(new BDLocator(IDLE_LOC).toExternalForm()));
            idle = p;
            listen(p);
            p.realize();
            long end = System.currentTimeMillis() + 5000;
            while (p.getState() < Player.Realized && System.currentTimeMillis() < end) Thread.sleep(50);
            p.start();
        } catch (Throwable t) {
            noIdle = true;
            Player p = idle;
            idle = null;
            if (p != null) { try { p.close(); } catch (Throwable e) { } }
            log("silent clip: " + t);
        }
    }

    /** The silent clip ended: play it again (switching to it keeps the video going). */
    private synchronized void loopIdle(Player p) {
        if (idle != p || player != null) return;
        if (!selectIdle(p)) {
            idle = null;
            try { p.close(); } catch (Throwable e) { }
        }
    }

    /** Song or album finished, or Stop: switch the music player to the silent clip. */
    private synchronized void goIdle(Player p) {
        if (p == null || player != p) return;
        player = null;
        baseKnown = false;
        playing = -1;
        if (noIdle || noSwitch || !selectIdle(p)) {
            try { p.stop(); } catch (Throwable e) { }
            try { p.close(); } catch (Throwable e) { }
        } else {
            idle = p;
        }
        repaint();
    }

    private boolean selectIdle(Player p) {
        try {
            org.bluray.media.PlayListChangeControl ctl =
                (org.bluray.media.PlayListChangeControl) p.getControl("org.bluray.media.PlayListChangeControl");
            if (ctl == null) return false;
            try { p.stop(); } catch (Throwable e) { }
            ctl.selectPlayList(new BDLocator(IDLE_LOC));
            p.start();
            return true;
        } catch (Throwable t) {
            noIdle = true;
            log("silent clip switch: " + t);
            return false;
        }
    }

    /**
     * Starts a track by switching the running player to it. Returns false (and changes
     * nothing that matters) when the player has no switch control or the switch fails,
     * so the caller makes a new player instead.
     */
    private boolean switchTo(Player p, Library lib, Catalog.Album al, int track) {
        org.bluray.media.PlayListChangeControl ctl = null;
        try {
            ctl = (org.bluray.media.PlayListChangeControl) p.getControl("org.bluray.media.PlayListChangeControl");
        } catch (Throwable e) { }
        if (ctl == null) {
            noSwitch = true;
            log("this player has no playlist switch - using a new player for each start");
            return false;
        }
        String pl = Layout.five(al.playlistId());
        String loc = "bd://PLAYLIST:" + pl + ".MARK:" + Layout.five(markIndex(lib, track));
        player = p;                                       // it is the music player from now on
        if (idle == p) idle = null;
        ended = false;
        baseKnown = false;
        playLib = lib;
        playAlbum = al;
        playing = track;
        userJumpAt = System.currentTimeMillis();
        starved = false;
        lastPos = lib.tracks[track].offsetSec;
        try {
            long t0 = System.currentTimeMillis();
            try { p.stop(); } catch (Throwable e) { }
            switchedAt = System.currentTimeMillis();
            ctl.selectPlayList(new BDLocator(loc));
            startedAt = System.currentTimeMillis();
            lastEvent = "";
            p.start();
            if (!settle(p, lib, track, t0)) {
                noSwitch = true;
                log("playback did not move after a playlist switch - using a new player for each start");
                return false;
            }
            baseKnown = true;
            startClock();
            repaint();
            return true;
        } catch (Throwable e) {
            noSwitch = true;
            log("playlist switch: " + e + " - using a new player for each start");
            return false;
        }
    }

    /**
     * After start(): waits for the media time to move, logs it, and works out how the
     * player's time relates to the album timeline (mediaBase). False if it never moved.
     */
    private boolean settle(Player p, Library lib, int track, long t0) throws Exception {
        Library.Track t = lib.tracks[track];
        // wait until the media time starts moving (players differ in how long they take)
        Thread.sleep(300);
        double first = p.getMediaTime().getSeconds(), mt = first;
        while (System.currentTimeMillis() - t0 < 8000 && player == p) {
            Thread.sleep(250);
            mt = p.getMediaTime().getSeconds();
            if (Math.abs(mt - first) > 0.3) break;
        }
        double el = (System.currentTimeMillis() - t0) / 1000.0;
        log("track " + (track + 1) + " (chapter " + (markIndex(lib, track) + 1) + "): time " + secs(first) + " -> "
            + secs(mt) + " s after " + secs(el) + " s" + (Math.abs(mt - first) <= 0.3 ? " - NOT MOVING" : ""));
        if (mt > t.offsetSec - 1 && mt < t.offsetSec + el + 2) {
            mediaBase = 0;                             // started at the chapter mark
        } else if (t.offsetSec > 3 && mt >= -1 && mt < el + 2 && !noSeek) {
            log("mark ignored (at " + mt + " s), seeking to " + ((int) t.offsetSec) + " s");
            p.setMediaTime(new Time(t.offsetSec));
            mediaBase = 0;
        } else {
            mediaBase = mt - el - t.offsetSec;
            log("media time base " + mediaBase + " s");
        }
        return Math.abs(mt - first) > 0.3;
    }

    private synchronized void playFresh(Library lib, Catalog.Album al, int track) {
        // Keep the player and switch it to the new playlist/chapter: closing it and making a
        // new one stopped the video, which players (or TVs) answer with a few seconds of black.
        Player old = (player != null) ? player : idle;
        if (old != null && !noSwitch && switchTo(old, lib, al, track)) return;
        if (idle != null && idle != player) {                 // switching failed: drop the silent clip too
            Player i = idle;
            idle = null;
            try { i.close(); } catch (Throwable e) { }
        }
        switchedAt = 0;
        stopPlayer();
        ended = false;
        playLib = lib;
        playAlbum = al;
        playing = track;
        userJumpAt = System.currentTimeMillis();
        starved = false;
        lastPos = lib.tracks[track].offsetSec;
        Library.Track t = lib.tracks[track];
        String pl = Layout.five(al.playlistId());
        String[] locs = { "bd://PLAYLIST:" + pl + ".MARK:" + Layout.five(markIndex(lib, track)), "bd://PLAYLIST:" + pl };
        Player p = null;
        for (int i = 0; i < locs.length && p == null; i++) {
            try {
                p = Manager.createPlayer(new MediaLocator(new BDLocator(locs[i]).toExternalForm()));
            } catch (Throwable e) {
                log(locs[i] + ": " + e);
            }
        }
        if (p == null) {
            playing = -1;
            repaint();
            return;
        }
        try {
            player = p;
            listen(p);
            p.realize();
            long end = System.currentTimeMillis() + 5000;
            while (p.getState() < Player.Realized && System.currentTimeMillis() < end) Thread.sleep(50);
            try {
                GainControl g = p.getGainControl();
                if (g != null) {
                    g.setMute(false);
                    g.setLevel(1.0f);
                }
            } catch (Throwable e) { }
            startedAt = System.currentTimeMillis();
            lastEvent = "";
            long t0 = System.currentTimeMillis();
            p.start();
            settle(p, lib, track, t0);
            baseKnown = true;
            startClock();
        } catch (Throwable e) {
            problem("play: " + e);
            playing = -1;
        }
        repaint();
    }

    private boolean jump(int track) {
        if (track < 0 || player == null) return true;
        if (noSeek) return false;
        try {
            double target = mediaBase + playLib.tracks[track].offsetSec;
            userJumpAt = System.currentTimeMillis();
            lastPos = playLib.tracks[track].offsetSec;
            player.setMediaTime(new Time(target));
            Thread.sleep(700);
            double mt = player.getMediaTime().getSeconds();
            if (Math.abs(mt - target) > 3) {
                log("seek ignored (at " + ((int) mt) + " s, wanted " + ((int) target) + " s)");
                return false;
            }
            playing = track;
            repaint();
            return true;
        } catch (Throwable e) {
            log("seek: " + e);
            return false;
        }
    }

    private static String secs(double v) {
        long t = Math.round(v * 10);
        return (t / 10) + "." + Math.abs(t % 10);
    }

    private volatile long startedAt = 0;
    private volatile String lastEvent = "";

    private static int markIndex(Library lib, int track) {
        int m = 0;
        for (int i = 0; i < track; i++) if (lib.tracks[i].group >= 0) m++;
        return m;
    }

    private void closeIdle() {
        Player p = idle;
        idle = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable t) { }
            try { p.close(); } catch (Throwable t) { }
        }
    }

    private void stopPlayer() {
        Player p = player;
        player = null;
        baseKnown = false;
        playing = -1;
        if (p != null) {
            try { p.stop(); } catch (Throwable t) { }
            try { p.close(); } catch (Throwable t) { }
        }
        repaint();
    }

    private boolean clockStarted;

    private synchronized void startClock() {
        if (clockStarted) return;                         // one clock for the whole session
        clockStarted = true;
        new Thread(new Runnable() {
            public void run() {
                while (!cancel) {
                    try {
                        Player p = player;
                        if (p == null || playLib == null || !baseKnown) {
                            toastExpiry();
                            Thread.sleep(500);
                            continue;
                        }
                        double raw = p.getMediaTime().getSeconds() - mediaBase;
                        long nowMs = System.currentTimeMillis();
                        boolean ours = nowMs - userJumpAt < 3000;
                        // some players report 0 (or jump back) while starved of data: ignore that
                        if (!ours && lastPos > 5 && raw < lastPos - 5) {
                            Thread.sleep(500);
                            continue;
                        }
                        if (raw > lastPos + 0.05 && raw < lastPos + 3) starved = false;
                        position = raw;
                        if (Math.abs(position - lastPos) > 0.05) {
                            lastPos = position;
                            lastMove = nowMs;
                        }
                        int cur = -1;
                        for (int i = 0; i < playLib.tracks.length; i++) {
                            if (playLib.tracks[i].group >= 0 && playLib.tracks[i].offsetSec <= position + 0.05) cur = i;
                        }
                        if (cur >= 0 && playing >= 0) playing = cur;
                        if (!starved) followPosition(position);
                        clockRepaint(nowMs);
                        Thread.sleep(500);
                    } catch (Throwable t) {
                        try { Thread.sleep(500); } catch (InterruptedException e) { }
                    }
                }
            }
        }, "wv-clock").start();
    }

    /* ================================================================ files / ids */

    private void readIds() throws Exception {
        InputStream in = new FileInputStream(vfsRoot + "/CERTIFICATE/id.bdmv");
        byte[] b = new byte[60];
        int o = 0;
        while (o < 60) {
            int n = in.read(b, o, 60 - o);
            if (n <= 0) break;
            o += n;
        }
        in.close();
        long org = ((b[40] & 0xFFL) << 24) | ((b[41] & 0xFF) << 16) | ((b[42] & 0xFF) << 8) | (b[43] & 0xFF);
        orgHex = Long.toHexString(org);
        StringBuffer s = new StringBuffer();
        for (int i = 44; i < 60; i++) {
            String h = Integer.toHexString(b[i] & 0xFF);
            if (h.length() < 2) h = "0" + h;
            s.append(h);
        }
        String d = s.toString();
        while (d.length() > 1 && d.charAt(0) == '0') d = d.substring(1);
        discHex = d;
    }

    static String readLine(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            return r.readLine();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable t) { }
        }
    }

    static String[] readLines(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
            Vector v = new Vector();
            String l;
            while ((l = r.readLine()) != null) v.addElement(l);
            String[] a = new String[v.size()];
            v.copyInto(a);
            return a;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable t) { }
        }
    }

    static String[] split(String s, char c) {
        Vector v = new Vector();
        int p = 0;
        while (true) {
            int q = s.indexOf(c, p);
            if (q < 0) { if (p < s.length()) v.addElement(s.substring(p)); break; }
            if (q > p) v.addElement(s.substring(p, q));
            p = q + 1;
        }
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }

    static byte[] readAll(String path) throws Exception {
        InputStream in = new FileInputStream(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static void writeFile(File f, byte[] b) throws Exception {
        OutputStream o = new FileOutputStream(f);
        try { o.write(b); } finally { o.close(); }
    }

    static void copy(String src, File dst) throws Exception {
        writeFile(dst, readAll(src));
    }

    /* ================================================================ ui */

    /*
     * Diagnostics (the log lines and the speed lines) are hidden unless switched on: from
     * the album menu (remembered in the player's storage), from options.txt on the disc
     * ("diagnostics=on"), or automatically for the session when something goes wrong.
     * notice() messages are for the listener and show for a few seconds either way.
     */
    private volatile boolean diag = false;
    private volatile String toast;
    private volatile long toastAt;

    void notice(String s) {
        toast = s;
        toastAt = System.currentTimeMillis();
        log(s);
    }

    void problem(String s) {
        diag = true;
        log(s);
    }

    private void loadDiag() {
        try {
            byte[] b = readAll(vfsRoot + "/BDMV/JAR/00000/options.txt");
            java.util.StringTokenizer lines = new java.util.StringTokenizer(new String(b), "\r\n");
            while (lines.hasMoreTokens()) {
                String l = lines.nextToken().trim().toLowerCase();
                if (l.startsWith("#")) continue;
                if (l.equals("diagnostics=on")) diag = true;
                else if (l.equals("diagnostics=off")) diag = false;
            }
        } catch (Throwable t) { }
        try {
            File f = new File(budaDir, "wvdiag.txt");
            if (f.exists()) diag = new String(readAll(f.getPath())).trim().equals("1");
        } catch (Throwable t) { }
    }

    private void setDiag(boolean on) {
        diag = on;
        try {
            writeFile(new File(budaDir, "wvdiag.txt"), (on ? "1\n" : "0\n").getBytes());
        } catch (Throwable t) { }
    }

    public void log(String s) {
        System.out.println("[WvPlayer] " + s);
        synchronized (log) {
            log.addElement(s);
            while (log.size() > 6) log.removeElementAt(0);
        }
        repaint();
    }

    private void repaint() {
        if (screen != null) screen.repaint();
    }

    /*
     * The clock runs every 500 ms. Redrawing the whole 1920x1080 screen each time was a
     * large part of the Oppo's CPU time, so: a full redraw only when the view changes (or
     * every 5 s for the decoding progress), otherwise only the elapsed time and its bar,
     * once per displayed second.
     */
    private String clockView = "";
    private long clockSec = -1, clockFull = 0;

    static final long TOAST_MS = 8000;
    private boolean toastVisible;

    /** Redraws once when a message's time is up, so it disappears. */
    private void toastExpiry() {
        boolean vis = toast != null && System.currentTimeMillis() - toastAt < TOAST_MS;
        if (vis != toastVisible) {
            toastVisible = vis;
            repaint();
        }
    }

    private void clockRepaint(long nowMs) {
        if (screen == null) return;
        toastExpiry();
        boolean idle = idleView();
        long step = idle ? (nowMs / 60000) % 4 : 0;     // the now-playing view moves each minute
        String view = state + "/" + playing + "/" + idle + "/" + step + "/" + waitingFor;
        long sec = (long) position;
        if (!view.equals(clockView) || nowMs - clockFull > 5000) {
            clockView = view;
            clockSec = sec;
            clockFull = nowMs;
            screen.repaint();
            return;
        }
        if (sec == clockSec) return;
        clockSec = sec;
        int w = screen.getWidth() > 0 ? screen.getWidth() : 1920;
        double k = w / 1920.0;
        if (idle) {                                       // bar + time in the now-playing view
            int x0 = 140 + ((step == 1 || step == 2) ? 60 : 0);
            int y0 = 130 + ((step >= 2) ? 40 : 0);
            screen.repaint((int) (x0 * k), (int) ((y0 + 415) * k), (int) ((1920 - x0) * k), (int) (95 * k));
        } else {                                          // "Playing ..." under the cover
            screen.repaint((int) (100 * k), (int) (848 * k), (int) (620 * k), (int) (100 * k));
        }
    }

    private static String clip(String s, int max) {
        return (s.length() <= max) ? s : s.substring(0, max - 2) + "..";
    }

    private static String mmss(double sec) {
        if (sec < 0) sec = 0;
        long s = (long) sec;
        long h = s / 3600;
        long m = (s / 60) % 60;
        s %= 60;
        return (h > 0 ? h + ":" + (m < 10 ? "0" : "") : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    private volatile long lastMove = 0;
    private volatile double lastPos = -1;

    private boolean idleView() {
        long now = System.currentTimeMillis();
        return playing >= 0 && playLib != null && (state == S_ALBUMS || state == S_TRACKS)
               && now - lastKey > IDLE_MS && now - lastMove < 3000;
    }

    private String playingCoverKey() {
        String pl = Layout.five(playAlbum.playlistId());
        String f = (String) covers.get(pl + "_" + playing);
        if (f == null) f = (String) covers.get(pl);
        if (f != null) return f;
        return Art.trackKey(playLib, playing, playAlbum.dir);
    }

    /** Drawn in 1920x1080 design units, scaled to the current graphics plane. */
    private class Screen extends Component {
        private double k;
        private Graphics g;

        public void paint(Graphics gr) {
            Container parent = getParent();
            if (parent != null && parent.getWidth() > 0
                    && (getWidth() != parent.getWidth() || getHeight() != parent.getHeight())) {
                setBounds(0, 0, parent.getWidth(), parent.getHeight());
            }
            int w = getWidth() > 0 ? getWidth() : 1920;
            k = w / 1920.0;
            g = gr;
            g.setColor(new Color(14, 16, 26));
            g.fillRect(0, 0, w, getHeight() > 0 ? getHeight() : 1080);
            if (idleView()) paintNowPlaying();
            else if (state == S_ALBUMS) paintAlbums();
            else if (state == S_TRACKS) paintTracks();
            else paintStatus();
            paintLog();
        }

        private void paintStatus() {
            text(headline, 110, 110, 50, Color.white, 60);
            // the detail wraps (an error can be a few sentences)
            String d = detail == null ? "" : detail;
            int y = 162, lines = 0;
            while (d.length() > 0 && lines < 5) {
                int cut = d.length();
                if (cut > 80) {                                  // TVs may crop the right edge
                    cut = d.lastIndexOf(' ', 80);
                    if (cut < 30) cut = 80;
                }
                text(d.substring(0, cut).trim(), 110, y, 26, new Color(150, 170, 210), 110);
                d = d.substring(cut).trim();
                y += 36;
                lines++;
            }
            if (progress >= 0) bar(110, Math.max(190, y - 8), 1700, 22, progress);
            if (state == S_ERROR && catalog != null) text("Press OK for the album list", 110, y + 60, 28, new Color(255, 210, 90), 60);
        }

        private void paintAlbums() {
            int n = catalog.albums.length;
            text("WavPack player", 110, 110, 50, Color.white, 60);
            int ready = readyCount();
            long fc = freeCache;
            text(n + " album" + (n == 1 ? "" : "s") + "   ·   " + (ready == n ? "all decoded"
                 : ready + " fully decoded" + (decodeAll ? "   ·   decoding in the background" : ""))
                 + (fc >= 0 ? "   ·   storage: " + (fc >= (10L << 30) ? (fc >> 30) + " GB" : (fc >> 20) + " MB") + " free" : ""),
                 110, 162, 26, new Color(150, 170, 210), 110);
            // cover of the selected album
            int listX = 110;
            if (albumSel < n) {
                Catalog.Album a = catalog.albums[albumSel];
                Art.Pic c = art.peek(albumCoverKey(a), s(560));
                if (c != null) {
                    g.drawImage(c.img, s(110) + (s(560) - c.w) / 2, s(230), c.w, c.h, null);
                }
                listX = 730;
            }
            String[] acts = albumActions();
            int rows = 13;
            int total = n + acts.length;
            int first = Math.max(0, Math.min(albumSel - rows / 2, total - rows));
            int y = 280;
            for (int i = first; i < total && i < first + rows; i++) {
                boolean isAlbum = i < n;
                if (i == albumSel) {
                    g.setColor(new Color(55, 80, 150));
                    rect(listX - 10, y - 34, 1820 - listX, 46);
                }
                if (isAlbum) {
                    Catalog.Album a = catalog.albums[i];
                    Color c = (i == albumSel) ? Color.white : new Color(205, 205, 210);
                    text(a.label(), listX, y, 28, c, 62);
                    String st = albumStatus(i);
                    if (st.length() > 0) text(st, 1720, y, 24, st.equals("ready") ? new Color(120, 230, 140)
                                                              : new Color(150, 170, 210), 8);
                    if (playAlbum == a && playing >= 0) text(">", listX - 40, y, 30, new Color(120, 230, 140), 2);
                } else {
                    text(acts[i - n], listX, y, 28, new Color(255, 210, 90), 60);
                }
                y += 48;
            }
            paintMiniPlaying(110, 880);
        }

        private void paintTracks() {
            Library lib = viewLib;
            Catalog.Album al = viewAlbum;
            String title = (lib.album != null) ? lib.album : al.title;
            String who = (lib.artist != null) ? lib.artist : al.artist;
            text(title, 110, 110, 50, Color.white, 60);
            double total = lib.totalSeconds();
            String st = albumStatus(al.index);
            text((who.length() > 0 ? who + "   ·   " : "") + lib.tracks.length + " tracks"
                 + (total > 0 ? ", " + mmss(total) : "")
                 + (st.equals("ready") ? "" : st.length() > 0 ? "   ·   " + st + " decoded" : "   ·   decodes as it plays"),
                 110, 162, 26, new Color(150, 170, 210), 110);

            boolean thisPlaying = (playLib == lib && playing >= 0);
            String key = thisPlaying ? playingCoverKey() : albumCoverKey(al);
            Art.Pic c = art.peek(key, s(560));
            if (c != null) g.drawImage(c.img, s(110) + (s(560) - c.w) / 2, s(230), c.w, c.h, null);
            int listX = 730;

            int n = trackRows();
            int rows = 13;
            int first = Math.max(0, Math.min(trackSel - rows / 2, n - rows));
            int y = 280;
            for (int i = first; i < n && i < first + rows; i++) {
                if (i == trackSel) {
                    g.setColor(new Color(55, 80, 150));
                    rect(listX - 10, y - 34, 1820 - listX, 46);
                }
                String a = trackScreenAction(i);
                if (a != null) {
                    text(a, listX, y, 28, a == A_BACK ? new Color(150, 170, 210) : new Color(255, 210, 90), 60);
                } else {
                    int t = i - firstTrackRow();
                    Library.Track tr = lib.tracks[t];
                    Color col = tr.error != null || tr.group < 0 ? new Color(120, 120, 120)
                              : (i == trackSel) ? Color.white : new Color(205, 205, 210);
                    text((t + 1 < 10 ? " " : "") + (t + 1) + "  " + tr.display(), listX, y, 28, col, 56);
                    String d = tr.error != null ? "unreadable" : tr.durationSec > 0 ? mmss(tr.durationSec) : "";
                    text(d, 1700, y, 28, col, 12);
                    if (thisPlaying && t == playing) text(">", listX - 40, y, 30, new Color(120, 230, 140), 2);
                    if (waitLib == lib && t == waitingFor) text("…", listX - 40, y, 30, new Color(255, 210, 90), 2);
                }
                y += 48;
            }
            paintMiniPlaying(110, 880);
        }

        /** Playing track summary under the cover. */
        private void paintMiniPlaying(int x, int y) {
            Library wl = waitLib;
            int wf = waitingFor;
            if (wf >= 0 && wl != null && wf < wl.tracks.length) {
                text("Decoding  " + wl.tracks[wf].display() + " ...", x, y, 28, new Color(255, 210, 90), 36);
                return;
            }
            if (playing < 0 || playLib == null) return;
            Library.Track t = playLib.tracks[playing];
            text("Playing  " + t.display(), x, y, 28, new Color(120, 230, 140), 36);
            String alb = (playLib.album != null) ? playLib.album : playAlbum.title;
            double el = Math.max(0, position - t.offsetSec);
            text(mmss(el) + " / " + mmss(t.durationSec) + "   " + alb, x, y + 38, 22, new Color(150, 170, 210), 46);
            bar(x, y + 52, 560, 8, el / Math.max(1, t.durationSec));
        }

        /** Large cover + track details; drifts a little every minute so nothing burns into the TV. */
        private void paintNowPlaying() {
            Library.Track t = playLib.tracks[playing];
            int step = (int) ((System.currentTimeMillis() / 60000) % 4);
            int dx = (step == 1 || step == 2) ? 60 : 0;
            int dy = (step >= 2) ? 40 : 0;
            int x0 = 140 + dx, y0 = 130 + dy;
            Art.Pic cover = art.peek(playingCoverKey(), s(780));
            int tx = x0;
            if (cover != null) {
                g.drawImage(cover.img, s(x0) + (s(780) - cover.w) / 2, s(y0), cover.w, cover.h, null);
                tx = x0 + 840;
            }
            int y = y0 + 120;
            text((playing + 1) + " / " + playLib.tracks.length, tx, y, 28, new Color(150, 170, 210), 20);
            y += 80;
            text(t.display(), tx, y, 52, Color.white, cover != null ? 26 : 50);
            y += 70;
            String who = (t.artist != null) ? t.artist : (playLib.artist != null ? playLib.artist : playAlbum.artist);
            text(who, tx, y, 34, new Color(205, 205, 210), cover != null ? 38 : 70);
            y += 54;
            String alb = (t.album != null) ? t.album : (playLib.album != null ? playLib.album : playAlbum.title);
            text(alb, tx, y, 30, new Color(150, 170, 210), cover != null ? 42 : 76);
            y += 110;
            double el = Math.max(0, position - t.offsetSec);
            bar(tx, y, 760, 10, el / Math.max(1, t.durationSec));
            text(mmss(el) + " / " + mmss(t.durationSec), tx, y + 60, 30, new Color(205, 205, 210), 20);
        }

        private void paintLog() {
            Vector lines = new Vector();
            Vector src = new Vector();
            boolean showDiag = diag;
            if (showDiag) {
                synchronized (log) {
                    for (int i = Math.max(0, log.size() - 3); i < log.size(); i++) src.addElement(log.elementAt(i));
                }
            } else {
                String t = toast;                                // only messages meant for the listener
                if (t != null && System.currentTimeMillis() - toastAt < TOAST_MS) src.addElement(t);
            }
            {
                for (int i = 0; i < src.size(); i++) {
                    String l = (String) src.elementAt(i);
                    while (l.length() > 95) {                       // wrap: TVs may crop the right edge
                        int cut = l.lastIndexOf(' ', 95);
                        if (cut < 40) cut = 95;
                        lines.addElement(l.substring(0, cut));
                        l = "    " + l.substring(cut).trim();
                    }
                    lines.addElement(l);
                }
            }
            int ly = 1028 - 26 * Math.min(lines.size(), 6);
            if (lines.size() > 0) {                              // dark band so the log reads over cover art
                g.setColor(new Color(12, 12, 20));
                rect(96, ly - 22, 1320, 1040 - (ly - 22));
            }
            String sp = showDiag ? speedLine : null;
            if (sp != null) {                                    // top right, where nothing else is drawn
                int cut = sp.indexOf('\n');
                g.setColor(new Color(12, 12, 20));
                rect(1090, 46, 740, cut > 0 ? 64 : 38);
                text(cut > 0 ? sp.substring(0, cut) : sp, 1100, 70, 18, new Color(120, 190, 140), 70);
                if (cut > 0) text(sp.substring(cut + 1), 1100, 96, 18, new Color(120, 190, 140), 70);
            }
            for (int i = Math.max(0, lines.size() - 6); i < lines.size(); i++) {
                text((String) lines.elementAt(i), 110, ly, showDiag ? 20 : 24,
                     showDiag ? new Color(150, 150, 165) : new Color(225, 225, 235), 100);
                ly += 26;
            }
        }

        private void bar(int x, int y, int w, int h, double f) {
            g.setColor(new Color(50, 60, 90));
            rect(x, y, w, h);
            g.setColor(new Color(90, 200, 120));
            rect(x, y, (int) (w * Math.max(0, Math.min(1, f))), h);
        }

        private final java.util.Hashtable fonts = new java.util.Hashtable();

        /** Fonts are made once per size (a new Font per text call kept the collector busy). */
        private Font font(int px) {
            Integer key = new Integer(px);
            Font f = (Font) fonts.get(key);
            if (f == null) {
                f = new Font(FONT, Font.PLAIN, px);
                fonts.put(key, f);
            }
            return f;
        }

        private void text(String str, int x, int y, int size, Color c, int maxChars) {
            if (str == null) return;
            g.setFont(font(s(size)));
            g.setColor(c);
            g.drawString(clip(str, maxChars), s(x), s(y));
        }

        private void rect(int x, int y, int w, int h) {
            g.fillRect(s(x), s(y), s(w), s(h));
        }

        private int s(int v) { return (int) Math.round(v * k); }
    }
}
