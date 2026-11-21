package com.rhythmstudio.mp4

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.UsedByGodot
import java.io.File

/**
 * RhythmMP4 (Android only, experimental):
 * Decodes H.264/AAC MP4 using Android MediaPlayer (system decoder).
 * Places a TextureView behind Godot's transparent SurfaceView.
 * REQUIRES Godot 4.5+ transparent window export & Android build.
 * Layer composition must be verified on the target tablet.
 */
class GodotAndroidPlugin(godot: Godot) : GodotPlugin(godot) {
    override fun getPluginName(): String = "RhythmMP4"

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var videoView: TextureView? = null
    @Volatile private var currentFile: String = ""
    @Volatile private var lastError: String = ""
    @Volatile private var decoderInfo: String = "Android MediaPlayer"
    @Volatile private var shouldAutoStart: Boolean = false

    private fun ensureView() {
        if (videoView != null) return
        val host = activity ?: return
        val root = host.findViewById<ViewGroup>(android.R.id.content)
        if (root !is FrameLayout) {
            lastError = "Activity content root is not FrameLayout"
            return
        }
        val view = TextureView(host)
        view.isOpaque = false
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                initMediaPlayer()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fitCenterCrop() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                player?.setSurface(null)
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        root.addView(view, 0, FrameLayout.LayoutParams(-1,-1))
        videoView = view
        if (view.isAvailable) initMediaPlayer()
    }

    private fun fitCenterCrop() {
        val view = videoView ?: return
        val p = player ?: return
        val vw = view.width.toFloat(); val vh = view.height.toFloat()
        val mw = p.videoWidth.toFloat(); val mh = p.videoHeight.toFloat()
        if (vw <= 0 || vh <= 0 || mw <= 0 || mh <= 0) return
        val scale = maxOf(vw / mw, vh / mh)
        val displayedW = mw * scale; val displayedH = mh * scale
        val transform = Matrix()
        transform.setScale(displayedW / vw, displayedH / vh, vw / 2f, vh / 2f)
        view.setTransform(transform)
    }

    private fun initMediaPlayer() {
        val v = videoView ?: return
        if (!v.isAvailable || currentFile.isEmpty()) return
        player?.release()
        val mp = MediaPlayer()
        player = mp
        try {
            val surface = Surface(v.surfaceTexture)
            mp.setSurface(surface)
            surface.release()
            mp.setDataSource(currentFile)
            mp.setOnPreparedListener {
                fitCenterCrop()
                if (shouldAutoStart) it.start()
            }
            mp.setOnErrorListener { _, what, extra ->
                lastError = "Android MediaPlayer error: $what, $extra"
                true
            }
            mp.setOnCompletionListener { shouldAutoStart = false }
            mp.prepareAsync()
        } catch (e: Exception) {
            lastError = e.message ?: "MP4 preparation failed"
            mp.release()
            if (player === mp) player = null
        }
    }

    @UsedByGodot fun playFile(path: String): Boolean {
        if (!File(path).exists() || !path.lowercase().endsWith(".mp4")) {
            lastError = "Missing/invalid MP4 file: $path"
            return false
        }
        currentFile = path
        shouldAutoStart = true
        lastError = ""
        runOnHostThread {
            if (videoView == null) {
                ensureView()
            } else {
                initMediaPlayer()
            }
        }
        return true // Async request accepted; not a playback-success guarantee.
    }

    @UsedByGodot fun pausePlayback() {
        shouldAutoStart = false
        runOnHostThread { try { if (player?.isPlaying == true) player?.pause() } catch (_: Exception) {} }
    }
    @UsedByGodot fun resumePlayback() {
        shouldAutoStart = true
        runOnHostThread { try { player?.start() } catch (_: Exception) {} }
    }
    @UsedByGodot fun stopPlayback() {
        shouldAutoStart = false
        runOnHostThread {
            try { player?.release() } catch (_: Exception) {}
            player = null
            currentFile = ""
            videoView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
            videoView = null
        }
    }

    @UsedByGodot fun getPositionMs(): Int {
        return try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }
    }
    @UsedByGodot fun getDecoderInfo(): String = decoderInfo
    @UsedByGodot fun getLastError(): String = lastError
}
