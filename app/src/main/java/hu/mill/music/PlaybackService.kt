package hu.mill.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.media.MediaBrowserService
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Előtérszolgáltatás + Android Auto menü (MediaBrowserService).
 *  - életben tartja az appot lezárt képernyőn,
 *  - média-értesítés, lezárt képernyős vezérlő, fülhallgató / kormánygombok,
 *  - Android Auto: lejátszókártya a navigáció mellett, listák/rádiók böngészése, hangvezérlés.
 * Maga a hang a telefonos felület WebView-iban szól (YouTube oldal, ill. <audio>).
 */
class PlaybackService : MediaBrowserService() {

    data class PState(
        val title: String, val artist: String, val playing: Boolean,
        val pos: Long, val dur: Long, val art: String
    )

    companion object {
        private const val CH = "mm_playback"
        private const val NID = 42
        private const val STYLE_OK = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val LIST = 1
        private const val GRID = 2
        private const val MAX_ITEMS = 300

        var inst: PlaybackService? = null
        private var pending: PState? = null

        fun update(ctx: Context, title: String, artist: String, playing: Boolean, pos: Long, dur: Long, art: String) {
            val st = PState(title, artist, playing, pos, dur, art)
            val s = inst
            if (s != null) {
                s.setState(st)
                return
            }
            if (!playing) return
            pending = st
            try {
                ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var session: MediaSession
    private lateinit var wake: PowerManager.WakeLock
    private lateinit var wifi: WifiManager.WifiLock
    private val main = Handler(Looper.getMainLooper())
    private var state = PState("MillMusic", "", false, 0, 0, "")
    private var isFg = false
    private var artKey = ""
    private var artUri: Uri? = null
    private var artBmp: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "Lejátszás", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        session = MediaSession(this, "MillMusic")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = Bus.command("play")
            override fun onPause() = Bus.command("pause")
            override fun onStop() = Bus.command("pause")
            override fun onSkipToNext() = Bus.command("next")
            override fun onSkipToPrevious() = Bus.command("prev")
            override fun onSeekTo(pos: Long) = Bus.command("seek", pos)
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = Bus.command("playId", 0, mediaId ?: "")
            override fun onPlayFromSearch(query: String?, extras: Bundle?) = Bus.command("search", 0, query ?: "")
        })
        session.setSessionActivity(openIntent())
        session.isActive = true
        sessionToken = session.sessionToken

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MillMusic:play")
            .apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MillMusic:wifi")
            .apply { setReferenceCounted(false) }

        pushPlaybackState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            "toggle" -> Bus.command(if (state.playing) "pause" else "play")
            "next" -> Bus.command("next")
            "prev" -> Bus.command("prev")
            "close" -> {
                Bus.command("pause")
                stopForeground(STOP_FOREGROUND_REMOVE)
                isFg = false
                stopSelf()
                return START_NOT_STICKY
            }
        }
        pending?.let { state = it; pending = null }
        setState(state)
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NID, n)
        }
        isFg = true
    }

    fun setState(st: PState) {
        state = st
        // Ha az Android Auto indította a szolgáltatást (csak kötve van), lejátszáskor előtérbe kell lépni
        if (st.playing && !isFg) {
            try {
                startForegroundService(Intent(this, PlaybackService::class.java))
            } catch (_: Exception) {
            }
        }
        if (st.art != artKey) loadArt(st.art)

        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, st.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, st.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (st.dur > 0) st.dur else -1L)
        artUri?.let {
            md.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it.toString())
            md.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, it.toString())
        }
        artBmp?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(md.build())
        pushPlaybackState()

        if (st.playing) {
            if (!wake.isHeld) wake.acquire(6 * 60 * 60 * 1000L)
            if (!wifi.isHeld) wifi.acquire()
        } else {
            if (wake.isHeld) wake.release()
            if (wifi.isHeld) wifi.release()
        }
        if (isFg) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NID, build())
    }

    private fun pushPlaybackState() {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
                )
                .setState(
                    if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    state.pos, if (state.playing) 1f else 0f
                )
                .build()
        )
    }

    /** A telefonos felület nem fut: megpróbáljuk elindítani, és üzenünk az autó kijelzőjére. */
    fun engineMissing() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        main.postDelayed({
            if (Bus.activity == null) {
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
                        .setState(PlaybackState.STATE_ERROR, 0, 0f)
                        .setErrorMessage("Nyisd meg egyszer a MillMusicot a telefonon")
                        .build()
                )
            }
        }, 2500)
    }

    private fun loadArt(key: String) {
        artKey = key
        artUri = ArtProvider.uriFor(key)
        artBmp = null
        if (key.isBlank()) return
        val ctx = applicationContext
        thread {
            val b = try {
                ArtProvider.fileForKey(ctx, key)?.let { f ->
                    BitmapFactory.decodeFile(f.absolutePath)?.let { bm ->
                        val m = maxOf(bm.width, bm.height)
                        if (m > 400) Bitmap.createScaledBitmap(bm, bm.width * 400 / m, bm.height * 400 / m, true) else bm
                    }
                }
            } catch (_: Exception) {
                null
            }
            main.post {
                if (artKey == key && b != null) {
                    artBmp = b
                    setState(state)
                }
            }
        }
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(icon: Int, label: String, act: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req, Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun build(): Notification {
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(state.title)
            .setContentText(state.artist)
            .setContentIntent(openIntent())
            .setOngoing(state.playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, "Előző", "prev", 1))
            .addAction(
                if (state.playing) action(R.drawable.ic_pause, "Szünet", "toggle", 2)
                else action(R.drawable.ic_play, "Lejátszás", "toggle", 2)
            )
            .addAction(action(R.drawable.ic_next, "Következő", "next", 3))
            .addAction(action(R.drawable.ic_close, "Bezár", "close", 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        artBmp?.let { b.setLargeIcon(it) }
        return b.build()
    }

    // ---------------- Android Auto menü ----------------

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserService.BrowserRoot? {
        val extras = Bundle().apply {
            putBoolean(STYLE_OK, true)
            putInt(STYLE_BROWSABLE, GRID)
            putInt(STYLE_PLAYABLE, LIST)
        }
        return MediaBrowserService.BrowserRoot("root", extras)
    }

    override fun onLoadChildren(parentId: String, result: MediaBrowserService.Result<MutableList<MediaBrowser.MediaItem>>) {
        val out = mutableListOf<MediaBrowser.MediaItem>()
        try {
            buildChildren(parentId, out)
        } catch (_: Exception) {
        }
        result.sendResult(out)
    }

    private fun item(
        id: String, title: String, sub: String?, icon: Uri?, browsable: Boolean, childStyle: Int = 0
    ): MediaBrowser.MediaItem {
        val d = MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).setIconUri(icon)
        if (childStyle != 0) d.setExtras(Bundle().apply {
            putInt(STYLE_BROWSABLE, childStyle)
            putInt(STYLE_PLAYABLE, childStyle)
        })
        return MediaBrowser.MediaItem(
            d.build(),
            if (browsable) MediaBrowser.MediaItem.FLAG_BROWSABLE else MediaBrowser.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun ids(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }

    private fun buildChildren(parentId: String, out: MutableList<MediaBrowser.MediaItem>) {
        val lib = Library.get(this)
        val tracks = lib.optJSONObject("tracks") ?: JSONObject()
        val lists = lib.optJSONArray("lists") ?: JSONArray()
        fun list(id: String): JSONObject? {
            for (i in 0 until lists.length()) {
                val l = lists.optJSONObject(i)
                if (l?.optString("id") == id) return l
            }
            return null
        }
        fun track(tid: String, mediaId: String) {
            val t = tracks.optJSONObject(tid) ?: return
            out += item(mediaId, t.optString("t"), t.optString("a"), ArtProvider.uriFor(t.optString("i")), false)
        }

        when {
            // Az első szint fülekként jelenik meg (max. 4)
            parentId == "root" -> {
                out += item("now", "Most", null, ArtProvider.emojiUri("✨"), true, GRID)
                out += item("lists", "Playlistek", null, ArtProvider.emojiUri("📚"), true, GRID)
                out += item("radio", "Rádiók", null, ArtProvider.emojiUri("📻"), true, LIST)
                out += item("rec", "Ajánlott", null, ArtProvider.emojiUri("💡"), true, LIST)
            }
            parentId == "now" -> {
                for (lid in ids(lib.optJSONArray("season"))) {
                    val l = list(lid) ?: continue
                    val n = l.optJSONArray("items")?.length() ?: 0
                    out += item("ls|$lid", l.optString("name"), "Kevert lejátszás · $n szám", ArtProvider.emojiUri(l.optString("emo")), false)
                }
                out += item("recent", "Legutóbb hallgatott", null, ArtProvider.emojiUri("🕘"), true, LIST)
            }
            parentId == "lists" -> {
                for (i in 0 until lists.length()) {
                    val l = lists.optJSONObject(i) ?: continue
                    val n = l.optJSONArray("items")?.length() ?: 0
                    if (n == 0) continue
                    out += item("list:" + l.optString("id"), l.optString("name"), "$n szám", ArtProvider.emojiUri(l.optString("emo")), true, LIST)
                }
            }
            parentId.startsWith("list:") -> {
                val lid = parentId.removePrefix("list:")
                val l = list(lid) ?: return
                out += item("ls|$lid", "Keverés", l.optString("name"), ArtProvider.emojiUri("🔀"), false)
                ids(l.optJSONArray("items")).take(MAX_ITEMS).forEach { track(it, "lp|$lid|$it") }
            }
            parentId == "radio" -> ids(lib.optJSONArray("radios")).take(MAX_ITEMS).forEach { track(it, "t|$it") }
            parentId == "rec" -> ids(lib.optJSONArray("rec")).take(MAX_ITEMS).forEach { track(it, "t|$it") }
            parentId == "recent" -> ids(lib.optJSONArray("recent")).take(MAX_ITEMS).forEach { track(it, "t|$it") }
        }
    }

    /** A felület új könyvtár-pillanatképet küldött → frissüljön az autó menüje. */
    fun libraryChanged() {
        listOf("root", "now", "lists", "radio", "rec", "recent").forEach { notifyChildrenChanged(it) }
        val lists = Library.get(this).optJSONArray("lists") ?: return
        for (i in 0 until lists.length()) {
            lists.optJSONObject(i)?.optString("id")?.let { notifyChildrenChanged("list:$it") }
        }
    }

    override fun onDestroy() {
        inst = null
        try { if (wake.isHeld) wake.release() } catch (_: Exception) {}
        try { if (wifi.isHeld) wifi.release() } catch (_: Exception) {}
        session.release()
        super.onDestroy()
    }
}
