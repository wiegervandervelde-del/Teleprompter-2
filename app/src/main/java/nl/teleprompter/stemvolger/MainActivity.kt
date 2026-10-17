package nl.teleprompter.stemvolger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nl.teleprompter.stemvolger.databinding.ActivityMainBinding
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importText(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            binding.script.setText(settings.script.ifEmpty { getString(R.string.sample_script) })
        }
        binding.script.doAfterTextChanged {
            settings.script = it?.toString().orEmpty()
            updateStats()
        }
        updateStats()
        handleShareIntent(intent)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> openDocument.launch(arrayOf("text/*"))
                R.id.action_language -> chooseLanguage()
                R.id.action_clear -> confirmClear()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        binding.start.setOnClickListener {
            if (binding.script.text.isNullOrBlank()) {
                Toast.makeText(this, R.string.empty_script, Toast.LENGTH_SHORT).show()
            } else {
                settings.script = binding.script.text.toString()
                startActivity(Intent(this, PrompterActivity::class.java))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        binding.script.setText(shared)
    }

    private fun updateStats() {
        val words = ScriptMatcher.tokenize(binding.script.text?.toString().orEmpty()).size
        // ~140 spoken words per minute is a typical presentation pace.
        val minutes = max(1, (words / 140.0).roundToInt())
        binding.stats.text = getString(R.string.word_count, words, minutes)
    }

    private fun importText(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            if (text != null) binding.script.setText(text)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseLanguage() {
        val langs = Settings.LANGUAGES
        val current = langs.indexOfFirst { it.first == settings.language }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(langs.map { it.second }.toTypedArray(), current) { dialog, which ->
                settings.language = langs[which].first
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.clear_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> binding.script.setText("") }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
