package dev.veil.launcher.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.veil.launcher.MediaListener

/** Tracks the active media session (Spotify, Nuvio, …) for the "now playing" pill. */
class NowPlaying(private val context: Context) {
    /** Immutable: every change creates a new Track, so Compose can skip parts that didn't change. */
    @androidx.compose.runtime.Immutable
    data class Track(
        val pkg: String,
        val title: String,
        val artist: String?,
        val album: String? = null,
        val art: ImageBitmap?,
        /** Larger copy of the album art for Veil Ambient. */
        val artLarge: ImageBitmap?,
        val playing: Boolean,
        /** Song length in ms, 0 if unknown. */
        val durationMs: Long = 0,
        /** Position reported by the player, and when (elapsedRealtime) it was reported. */
        val positionMs: Long = 0,
        val positionAt: Long = 0,
        val speed: Float = 1f,
    ) {
        /** Current position, extrapolated while playing. */
        fun positionNow(): Long {
            val p = if (playing) positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong() else positionMs
            return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
        }
    }

    var track by mutableStateOf<Track?>(null)
        private set

    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    /**
     * Resized art for one song. [isIcon] marks the app-icon fallback (used when the player offers no
     * artwork at all, e.g. Spotify ads): the real cover replaces it as soon as it turns up.
     */
    private class Art(
        val key: String,
        val small: ImageBitmap,
        val large: ImageBitmap,
        val isIcon: Boolean = false,
        /** Fingerprint of the bitmap it was made from: players sometimes re-send the previous cover. */
        val sig: Long = 0,
    )
    private var artCache: Art? = null
    /** Song whose artwork hasn't arrived yet, and since when. */
    private var missingArtKey: String? = null
    private var missingArtSince = 0L
    private val recheck = Runnable { recompute() }
    /** Album art is resized here, off the UI thread (song changes stay smooth). */
    private val artWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "veil-art").apply { isDaemon = true } }
    /** Song on screen at the last [recompute]: a resize finished for anything else is dropped. */
    private var displayedArtKey: String? = null
    /** Cover queued for resizing, and the fingerprint it was announced with: players re-report the
     *  same song many times a second, but a changed picture for the same song must re-queue. */
    private var artInFlightKey: String? = null
    private var artInFlightSig = 0L
    /** Fingerprint of the real cover of each of the last few songs. A report holding another song's
     *  picture is the previous cover re-sent between tracks, not the new song's art. */
    private val recentSigs = LinkedHashMap<String, Long>()
    /** Re-evaluates when a paused session reaches the hide time. */
    private val hideCheck = Runnable { recompute() }
    /** When we first saw each app paused (fallback if the player doesn't timestamp its state). */
    private val pausedSeen = HashMap<String, Long>()

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
        override fun onMetadataChanged(metadata: MediaMetadata?) = recompute()
        override fun onSessionDestroyed() = recompute()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    /** Starts listening; does nothing if the media permission hasn't been granted. */
    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            attach(msm.getActiveSessions(component))
        } catch (_: SecurityException) {
            track = null
        }
    }

    fun stop() {
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(hideCheck)
        artInFlightKey = null
        artInFlightSig = 0L
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = emptyList()
    }

    fun togglePlayPause() {
        val c = current() ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list.filter { it.packageName != context.packageName }
        controllers.forEach { it.registerCallback(callback, handler) }
        recompute()
    }

    /** Prefer whatever is playing; otherwise the most recent paused session. */
    private fun current(): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull {
                it.playbackState?.state in setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) && it.metadata != null
            }

    private fun recompute() {
        val c = current()
        val md = c?.metadata
        if (c == null || md == null) { track = null; return }
        // Paused for a while (e.g. Spotify left open): treat it as nothing playing, so the pill goes away
        // and Ambient shows your shows again. Pressing play brings it straight back.
        handler.removeCallbacks(hideCheck)
        val state = c.playbackState
        if (state?.state == PlaybackState.STATE_PLAYING) {
            pausedSeen.remove(c.packageName)
        } else {
            val now = SystemClock.elapsedRealtime()
            val seen = pausedSeen.getOrPut(c.packageName) { now }
            // The earlier of: when the player says its state last changed, and when we first saw it paused
            // (some players refresh their timestamp while paused).
            val since = minOf(seen, state?.lastPositionUpdateTime?.takeIf { it in 1..now } ?: seen)
            val pausedFor = now - since
            if (pausedFor >= HIDE_AFTER_PAUSE_MS) { track = null; return }
            handler.postDelayed(hideCheck, HIDE_AFTER_PAUSE_MS - pausedFor + 500)
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) { track = null; return }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        fun keyOf(pkg: String, title: String, artist: String?) = "$pkg|$title|$artist"
        val artKey = keyOf(c.packageName, title, artist)
        // The cover we're currently showing belongs to this song: the only case where carrying it over is honest.
        val sameSong = track?.let { keyOf(it.pkg, it.title, it.artist) == artKey } ?: false
        displayedArtKey = artKey
        // Real artwork only. The app icon is a last resort when nothing else is on offer: players
        // (Spotify especially, on ads and between tracks) often send only the icon for a moment before
        // the cover, and showing that first read as "wrong image".
        val cover: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val icon = cover ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val sig = fingerprintOf(cover)
        // A picture another recent song was announced with is the previous cover re-sent (players do
        // this between tracks), not this song's art — unless the song genuinely shares the cover.
        val stale = sig != 0L && recentSigs.any { (k, s) -> k != artKey && s == sig }
        // Same song *and* same picture — or the cached real cover while the player re-sends the
        // previous one: nothing to do. Anything else (new song, or the player swapped the artwork)
        // is rebuilt, so artwork follows the player instead of sticking for the song.
        val cached = artCache?.takeIf {
            it.key == artKey && (!it.isIcon || cover == null) &&
                (it.sig == sig || (stale && !it.isIcon) || (cover == null && !it.isIcon))
        }
        var small: ImageBitmap? = cached?.small
        var large: ImageBitmap? = cached?.large
        if (cached == null) {
            fun scaled(b: Bitmap, size: Int): ImageBitmap? {
                val s = minOf(size, maxOf(b.width, b.height))
                return runCatching { Bitmap.createScaledBitmap(b, s, s, true).asImageBitmap() }.getOrNull()
            }
            fun save(s96: ImageBitmap, s640: ImageBitmap, isIcon: Boolean, sig: Long = 0) {
                artCache = Art(artKey, s96, s640, isIcon, sig)
                if (!isIcon && sig != 0L) {
                    recentSigs[artKey] = sig
                    while (recentSigs.size > 4) recentSigs.remove(recentSigs.keys.first())
                }
                missingArtKey = null
                handler.removeCallbacks(recheck)
            }
            val now = System.currentTimeMillis()
            if (stale && missingArtKey != artKey) { missingArtKey = artKey; missingArtSince = now }
            // Within a short grace the re-sent picture still counts as "the cover hasn't arrived
            // yet"; after it the picture is accepted (covers genuinely shared by tracks of one album).
            val waitForArt = stale && now - missingArtSince < 1_500
            when {
                cover != null && !waitForArt -> {
                    // Resize on the worker (players re-report the same song many times a second, so
                    // only one job at a time — but a changed picture re-queues); the art shows up on
                    // the recompute that follows.
                    if (artInFlightKey != artKey || artInFlightSig != sig) {
                        artInFlightKey = artKey
                        artInFlightSig = sig
                        artWorker.execute {
                            val s96 = scaled(cover, 96); val s640 = scaled(cover, 640)
                            handler.post {
                                // Still the latest request for this song? (A newer song or a newer
                                // picture re-queued meanwhile: this late result must not save, or it
                                // would show the previous song's cover.)
                                val latest = artInFlightKey == artKey && artInFlightSig == sig
                                if (latest) { artInFlightKey = null; artInFlightSig = 0L }
                                if (s96 != null && s640 != null && latest && displayedArtKey == artKey) {
                                    save(s96, s640, false, sig)
                                }
                                if (controllers.isNotEmpty()) recompute() // not after stop()
                            }
                        }
                    }
                    // Another song's cover is never carried over; for this same song keep it until the
                    // resized one lands (avoids flashing the placeholder on every state update).
                    if (sameSong) { small = track?.art; large = track?.artLarge }
                }
                cover != null -> {
                    // The previous song's cover, still held (or re-sent) for this one: wait for a
                    // different picture. A new song shows the placeholder — it reads as "loading",
                    // not as "wrong song".
                    if (sameSong) { small = track?.art; large = track?.artLarge }
                    handler.removeCallbacks(recheck)
                    handler.postDelayed(recheck, 300)
                }
                icon != null -> {
                    // Only the app icon so far: wait briefly for the real cover (players usually send it a
                    // moment later) instead of flashing the icon.
                    if (missingArtKey != artKey) { missingArtKey = artKey; missingArtSince = now }
                    if (now - missingArtSince >= 2_000) {
                        val s96 = scaled(icon, 96); val s640 = scaled(icon, 640)
                        if (s96 != null && s640 != null) { small = s96; large = s640; save(s96, s640, true) }
                    } else {
                        // Same song, bitmap dropped for a moment (icon still there): keep the cover.
                        if (sameSong) { small = track?.art; large = track?.artLarge }
                        handler.removeCallbacks(recheck)
                        handler.postDelayed(recheck, 2_100)
                    }
                }
                sameSong -> {
                    // The same song reported no artwork for a moment (players sometimes omit it): keep the
                    // cover we have. A *different* song never reuses the previous one (Spotify's ads and
                    // back-to-back tracks send the new title first and the artwork a beat later; showing
                    // the old cover there read as "wrong song", while the placeholder reads as "loading").
                    if (missingArtKey != artKey) { missingArtKey = artKey; missingArtSince = now }
                    if (now - missingArtSince < 4_000) {
                        small = track?.art; large = track?.artLarge
                        handler.removeCallbacks(recheck)
                        handler.postDelayed(recheck, 4_100)
                    }
                }
            }
        }
        val ps = c.playbackState
        track = Track(
            pkg = c.packageName,
            title = title,
            artist = artist,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = small,
            artLarge = large,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionMs = ps?.position ?: 0,
            positionAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }

    /** Cheap content fingerprint (an 8x8 thumbnail): tells whether two reports hold the same picture. */
    private fun fingerprintOf(b: Bitmap?): Long {
        if (b == null) return 0
        val s = runCatching { Bitmap.createScaledBitmap(b, 8, 8, true) }.getOrNull() ?: return 0
        val px = IntArray(64)
        s.getPixels(px, 0, 8, 0, 0, 8, 8)
        return px.fold(7L) { h, c -> h * 31 + c }
    }

    companion object {
        /** A paused player disappears from Veil after this long. */
        const val HIDE_AFTER_PAUSE_MS = 5 * 60_000L
    }
}
