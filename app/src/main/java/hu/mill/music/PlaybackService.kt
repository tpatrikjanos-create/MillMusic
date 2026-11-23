package hu.mill.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Előtérszolgáltatás: életben tartja az appot lezárt képernyőn,
 * média-értesítést és lezárt képernyős vezérlőt ad, fogadja a fülhallgató / Bluetooth gombokat.
 * Maga a hang a WebView-kban szól (YouTube oldal, ill. <audio> az mp3-hoz és rádióhoz).
 */
class PlaybackService : Service() {

    data class PState(val title: String, val artist: String, val playing: Boolean, val pos: Long, val dur: Long)

    companion object {
        private const val CH = "mm_playback"
        private const val NID = 42
        var inst: PlaybackService? = null
        private var pending: PState? = null

        fun update(ctx: Context, title: String, artist: String, playing: Boolean, pos: Long, dur: Long) {
            val st = PState(title, artist, playing, pos, dur)
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
    private var state = PState("MillMusic", "", false, 0, 0)

    override fun onBind(intent: Intent?): IBinder? = null

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
        })
        session.setSessionActivity(openIntent())
        session.isActive = true

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MillMusic:play")
            .apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MillMusic:wifi")
            .apply { setReferenceCounted(false) }
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
    }

    fun setState(st: PState) {
        state = st
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, st.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, st.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (st.dur > 0) st.dur else -1L)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(
                    if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    st.pos, if (st.playing) 1f else 0f
                )
                .build()
        )
        if (st.playing) {
            if (!wake.isHeld) wake.acquire(6 * 60 * 60 * 1000L)
            if (!wifi.isHeld) wifi.acquire()
        } else {
            if (wake.isHeld) wake.release()
            if (wifi.isHeld) wifi.release()
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NID, build())
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

    private fun build(): Notification =
        Notification.Builder(this, CH)
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
            .build()

    override fun onDestroy() {
        inst = null
        try { if (wake.isHeld) wake.release() } catch (_: Exception) {}
        try { if (wifi.isHeld) wifi.release() } catch (_: Exception) {}
        session.release()
        super.onDestroy()
    }
}
