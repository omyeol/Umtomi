package xyz.sabok.umtomi.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.util.LockManager
import xyz.sabok.umtomi.util.PortAppLock
import xyz.sabok.umtomi.util.Preferences

private fun passwordField(context: android.content.Context, style: PortStyle) = EditText(context).apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    hint = context.getString(R.string.settings_lock_password)
    setTextColor(style.foreground)
    setHintTextColor(style.muted)
    isSaveEnabled = false
}

private fun LinearLayout.applyLockInsets(style: PortStyle) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(padding.left + style.dp(18), padding.top + style.dp(16), padding.right + style.dp(18), padding.bottom + style.dp(16))
        insets
    }
}

class PortLockSettingsActivity : BaseActivity() {
    private val style by lazy { PortStyle(this) }
    private lateinit var form: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = style.stack().apply { setBackgroundColor(style.background) }
        root.applyLockInsets(style)
        form = style.stack()
        root.addView(ScrollView(this).apply { addView(form) })
        setContentView(root)
        style.applySystemBars(this, root)
        render()
    }

    private fun render() {
        form.removeAllViews()
        form.addView(style.action(getString(R.string.port_back)) { finish() })
        form.addView(style.text(getString(R.string.settings_app_lock), 31f, bold = true),
            style.matchWrap().apply { topMargin = style.dp(15); bottomMargin = style.dp(14) })
        val enabled = PortAppLock.enabled(this)
        form.addView(style.action(getString(if (enabled) R.string.port_lock_change else R.string.port_lock_create)) {
            promptPassword(enabled)
        }, style.matchWrap())
        if (!enabled) return
        val biometric = BiometricManager.from(this).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        addSwitch(R.string.settings_lock_biometrics, Preferences["port_lock_biometric", false], biometric) {
            Preferences["port_lock_biometric"] = it
        }
        val instant = Preferences["port_lock_immediately", true]
        addSwitch(R.string.port_lock_immediate, instant) {
            Preferences["port_lock_immediately"] = it
            render()
        }
        form.addView(style.action(getString(R.string.port_lock_wait, Preferences["port_lock_minutes", 5])) {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(Preferences["port_lock_minutes", 5].toString())
            }
            AlertDialog.Builder(this).setTitle(R.string.port_lock_wait_title).setView(input)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                    input.text.toString().toIntOrNull()?.coerceIn(1, 1440)?.let { Preferences["port_lock_minutes"] = it }
                    render()
                }.show()
        }.apply { isEnabled = !instant; alpha = if (instant) 0.42f else 1f }, style.matchWrap())
        form.addView(style.action(getString(R.string.port_lock_now)) {
            PortAppLock.requireUnlock()
            startActivity(Intent(this, PortUnlockActivity::class.java))
        }, style.matchWrap().apply { topMargin = style.dp(10) })
        form.addView(style.action(getString(R.string.port_lock_disable)) { promptPassword(existing = true, removing = true) },
            style.matchWrap().apply { topMargin = style.dp(10) })
    }

    private fun addSwitch(title: Int, value: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
        form.addView(SwitchCompat(this).apply {
            setText(title)
            setTextColor(style.foreground)
            minHeight = style.dp(52)
            isChecked = value
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.42f
            setOnCheckedChangeListener { _, checked -> change(checked) }
        }, style.matchWrap())
    }

    private fun promptPassword(existing: Boolean, removing: Boolean = false) {
        val inputs = style.stack().apply { setPadding(style.dp(16), 0, style.dp(16), 0) }
        val current = passwordField(this, style).apply { hint = getString(R.string.port_lock_current) }
        val next = passwordField(this, style).apply { hint = getString(R.string.port_lock_new) }
        val confirmation = passwordField(this, style).apply { hint = getString(R.string.port_lock_repeat) }
        if (existing) inputs.addView(current)
        if (!removing) { inputs.addView(next); inputs.addView(confirmation) }

        val dialog = AlertDialog.Builder(this).setTitle(R.string.settings_app_lock).setView(inputs)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                if (!removing && (next.length() < 4 || next.text.toString() != confirmation.text.toString())) {
                    confirmation.error = getString(R.string.port_lock_mismatch)
                    return@setOnClickListener
                }
                val old = current.text.toString().toCharArray()
                val password = next.text.toString().toCharArray()
                save.isEnabled = false
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        try {
                            if (existing && !PortAppLock.matches(this@PortLockSettingsActivity, old)) return@withContext false
                            if (removing) PortAppLock.remove(this@PortLockSettingsActivity)
                            else {
                                PortAppLock.save(this@PortLockSettingsActivity, password)
                                if (!existing) runCatching { LockManager(this@PortLockSettingsActivity).clearLocks() }
                            }
                            true
                        } catch (_: Exception) {
                            false
                        } finally {
                            old.fill('\u0000')
                            password.fill('\u0000')
                        }
                    }
                    if (success) {
                        PortAppLock.authorize()
                        dialog.dismiss()
                        render()
                    } else {
                        save.isEnabled = true
                        if (existing) current.error = getString(R.string.port_lock_failed)
                        else next.error = getString(R.string.port_lock_failed)
                    }
                }
            }
        }
        dialog.show()
    }
}

class PortUnlockActivity : AppCompatActivity() {
    private var waiting = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val style = PortStyle(this)
        val root = style.stack().apply {
            setBackgroundColor(style.background)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        root.applyLockInsets(style)
        val input = passwordField(this, style)
        root.addView(style.text(getString(R.string.app_name), 34f, bold = true), style.matchWrap())
        root.addView(input, style.matchWrap().apply { topMargin = style.dp(16) })
        val check = style.action(getString(R.string.port_lock_unlock), true) {
            if (!waiting) {
                waiting = true
                val password = input.text.toString().toCharArray()
                lifecycleScope.launch {
                    val valid = withContext(Dispatchers.IO) {
                        try { PortAppLock.matches(this@PortUnlockActivity, password) }
                        finally { password.fill('\u0000') }
                    }
                    waiting = false
                    if (valid) unlock()
                    else input.error = getString(R.string.port_lock_failed)
                }
            }
        }
        root.addView(check, style.matchWrap().apply { topMargin = style.dp(10) })
        input.setOnEditorActionListener { _, _, _ -> check.performClick(); true }
        setContentView(root)
        style.applySystemBars(this, root)
        if (Preferences["port_lock_biometric", false] &&
            BiometricManager.from(this).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS) {
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = unlock()
                })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.settings_app_lock))
                .setNegativeButtonText(getString(R.string.settings_lock_password)).setConfirmationRequired(false).build())
        }
    }

    private fun unlock() {
        PortAppLock.authorize()
        setResult(RESULT_OK)
        finish()
    }
}
