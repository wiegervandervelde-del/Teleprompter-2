package nl.teleprompter.stemvolger

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nl.teleprompter.stemvolger.databinding.ActivityPrompterBinding
import kotlin.math.abs
import kotlin.math.exp

class PrompterActivity : AppCompatActivity(), Choreographer.FrameCallback {

    private lateinit var binding: ActivityPrompterBinding
    private lateinit var settings: Settings
    private lateinit var matcher: ScriptMatcher
    private lateinit var spannable: Spannable
    private var follower: SpeechFollower? = null

    private val spokenSpan by lazy { ForegroundColorSpan(ContextCompat.getColor(this, R.color.prompt_spoken)) }
    private val currentSpan by lazy { ForegroundColorSpan(ContextCompat.getColor(this, R.color.prompt_current)) }

    private var playing = false
    private var userTouching = false
    private var lastFrameNanos = 0L
    private var scrollPos = 0f
    private var readingLineY = 0
    private var touchStartScrollY = 0
    private val scrollSettled = Runnable { onUserScrollFinished() }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) play() else showStatus(getString(R.string.state_no_permission))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrompterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.controls) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + PAD, PAD, bars.right + PAD, bars.bottom + PAD)
            insets
        }

        val script = settings.script
        matcher = ScriptMatcher(script)
        spannable = SpannableString(script)
        binding.text.setText(spannable, TextView.BufferType.SPANNABLE)
        spannable = binding.text.text as Spannable
        applyTextSize()
        applyMirror()

        // Put the reading line at 30% of the screen and pad the text so the first and last
        // lines can reach it.
        binding.scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val h = bottom - top
            readingLineY = (h * 0.3f).toInt()
            binding.text.setPadding(binding.text.paddingLeft, readingLineY, binding.text.paddingRight, h - readingLineY)
            binding.marker.translationY = readingLineY.toFloat()
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                binding.controls.isVisible = !binding.controls.isVisible
                return true
            }
        })
        binding.scroll.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    binding.scroll.removeCallbacks(scrollSettled)
                    if (!userTouching) touchStartScrollY = binding.scroll.scrollY
                    userTouching = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Give a fling time to settle, then continue from where the user scrolled to.
                    binding.scroll.postDelayed(scrollSettled, 700)
                }
            }
            false
        }

        binding.close.setOnClickListener { finish() }
        binding.toTop.setOnClickListener {
            matcher.position = 0
            updateHighlight()
            scrollPos = 0f
            binding.scroll.scrollTo(0, 0)
        }
        binding.playPause.setOnClickListener { if (playing) pause() else requestPlay() }
        binding.mirror.setOnClickListener {
            settings.mirror = !settings.mirror
            applyMirror()
        }
        binding.textSmaller.setOnClickListener { changeTextSize(-4) }
        binding.textLarger.setOnClickListener { changeTextSize(+4) }
        binding.slower.setOnClickListener { changeSpeed(-10) }
        binding.faster.setOnClickListener { changeSpeed(+10) }

        binding.modeGroup.check(if (settings.voiceMode) R.id.modeVoice else R.id.modeAuto)
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val wasPlaying = playing
            pause()
            settings.voiceMode = id == R.id.modeVoice
            updateModeControls()
            if (wasPlaying) requestPlay()
        }
        updateModeControls()
        updateHighlight()
        showStatus(getString(R.string.state_paused))

        if (savedInstanceState == null && settings.voiceMode) requestPlay()
    }

    override fun onResume() {
        super.onResume()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        super.onPause()
        pause()
        Choreographer.getInstance().removeFrameCallback(this)
    }

    // ---- Playback ---------------------------------------------------------------------------

    private fun requestPlay() {
        if (!settings.voiceMode) {
            play()
            return
        }
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> play()
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.permission_rationale)
                    .setPositiveButton(android.R.string.ok) { _, _ -> requestMic.launch(Manifest.permission.RECORD_AUDIO) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            else -> requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun play() {
        playing = true
        binding.playPause.setImageResource(R.drawable.ic_pause)
        binding.playPause.contentDescription = getString(R.string.pause)
        if (settings.voiceMode) {
            follower = SpeechFollower(this, settings.language, ::onHypothesis, ::onSpeechState).also { it.start() }
        } else {
            scrollPos = binding.scroll.scrollY.toFloat()
            showStatus(getString(R.string.state_auto, settings.scrollSpeed))
        }
    }

    private fun pause() {
        if (!playing) return
        playing = false
        follower?.stop()
        follower = null
        binding.playPause.setImageResource(R.drawable.ic_play)
        binding.playPause.contentDescription = getString(R.string.play)
        showStatus(getString(R.string.state_paused))
    }

    private fun onHypothesis(text: String) {
        if (userTouching) return
        if (matcher.onHeard(text)) {
            updateHighlight()
            if (matcher.isFinished) showStatus(getString(R.string.state_finished))
        }
    }

    private fun onSpeechState(state: SpeechFollower.State) {
        when (state) {
            SpeechFollower.State.LISTENING -> if (!matcher.isFinished) showStatus(getString(R.string.state_listening))
            SpeechFollower.State.HEARING -> if (!matcher.isFinished) showStatus(getString(R.string.state_hearing))
            SpeechFollower.State.STOPPED -> Unit
            SpeechFollower.State.NOT_AVAILABLE -> {
                pause()
                showStatus(getString(R.string.state_not_available))
            }
            SpeechFollower.State.NO_PERMISSION -> {
                pause()
                showStatus(getString(R.string.state_no_permission))
                openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.state_no_permission)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Scrolling --------------------------------------------------------------------------

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastFrameNanos == 0L) 0f else (frameTimeNanos - lastFrameNanos) / 1e9f
        lastFrameNanos = frameTimeNanos
        if (!userTouching && dt > 0f) {
            val maxScroll = (binding.text.height - binding.scroll.height).coerceAtLeast(0)
            if (settings.voiceMode) {
                val target = targetScrollY().toFloat()
                val current = binding.scroll.scrollY.toFloat()
                if (abs(target - current) >= 1f) {
                    // Exponential ease towards the line being read.
                    val step = (target - current) * (1f - exp(-dt * 5f))
                    binding.scroll.scrollTo(0, (current + step).toInt().coerceIn(0, maxScroll))
                }
            } else if (playing) {
                val density = resources.displayMetrics.density
                scrollPos = (scrollPos + settings.scrollSpeed * density * dt).coerceAtMost(maxScroll.toFloat())
                binding.scroll.scrollTo(0, scrollPos.toInt())
                if (scrollPos >= maxScroll) {
                    pause()
                    showStatus(getString(R.string.state_finished))
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Scroll offset that puts the line containing the current word on the reading line. */
    private fun targetScrollY(): Int {
        val layout = binding.text.layout ?: return binding.scroll.scrollY
        val words = matcher.words
        if (words.isEmpty()) return 0
        val index = matcher.position.coerceAtMost(words.size - 1)
        val line = layout.getLineForOffset(words[index].start)
        return layout.getLineTop(line)
    }

    private fun onUserScrollFinished() {
        userTouching = false
        scrollPos = binding.scroll.scrollY.toFloat()
        if (binding.scroll.scrollY == touchStartScrollY) return // just a tap
        val layout = binding.text.layout ?: return
        // The line under the reading marker becomes the new position.
        val y = binding.scroll.scrollY + readingLineY - binding.text.paddingTop
        val line = layout.getLineForVertical(y.coerceAtLeast(0))
        matcher.position = matcher.wordIndexAt(layout.getLineStart(line))
        updateHighlight()
    }

    // ---- Appearance -------------------------------------------------------------------------

    private fun updateHighlight() {
        spannable.removeSpan(spokenSpan)
        spannable.removeSpan(currentSpan)
        val words = matcher.words
        if (words.isEmpty()) return
        val pos = matcher.position
        val spokenEnd = if (pos >= words.size) spannable.length else words[pos].start
        if (spokenEnd > 0) spannable.setSpan(spokenSpan, 0, spokenEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (pos < words.size && settings.voiceMode) {
            spannable.setSpan(currentSpan, words[pos].start, words[pos].end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun updateModeControls() {
        val auto = !settings.voiceMode
        binding.slower.isVisible = auto
        binding.faster.isVisible = auto
        updateHighlight()
    }

    private fun changeTextSize(delta: Int) {
        settings.textSizeSp = (settings.textSizeSp + delta).coerceIn(16, 120)
        applyTextSize()
    }

    private fun applyTextSize() {
        binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.textSizeSp.toFloat())
    }

    private fun changeSpeed(delta: Int) {
        settings.scrollSpeed = (settings.scrollSpeed + delta).coerceIn(10, 400)
        showStatus(getString(R.string.state_auto, settings.scrollSpeed))
    }

    private fun applyMirror() {
        binding.mirrorContainer.scaleX = if (settings.mirror) -1f else 1f
    }

    private fun showStatus(text: String) {
        binding.status.text = text
    }

    private companion object {
        val PAD = (8 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }
}
