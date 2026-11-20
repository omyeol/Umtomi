/*
 *     Umtomi, Hitomi.la viewer for Android
 *     Copyright (C) 2020  tom5079
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package xyz.sabok.umtomi.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.util.LockManager
import xyz.sabok.umtomi.util.PortAppLock
import xyz.sabok.umtomi.util.Preferences
import xyz.sabok.umtomi.util.normalizeID

open class BaseActivity : AppCompatActivity() {

    private var locked: Boolean = true
    private var unlocking = false

    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unlocking = false
        if (it.resultCode == Activity.RESULT_OK) {
            locked = false
            findViewById<android.view.View>(android.R.id.content).visibility = android.view.View.VISIBLE
        } else
            finish()
    }

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locked = !PortAppLock.enabled(this) && !LockManager(this).locks.isNullOrEmpty()
    }

    @CallSuper
    override fun onResume() {
        super.onResume()

        if (Preferences["security_mode"])
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE)
        else
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (unlocking) return
        if (PortAppLock.enabled(this)) {
            if (!PortAppLock.unlocked) {
                unlocking = true
                findViewById<android.view.View>(android.R.id.content).visibility = android.view.View.INVISIBLE
                lockLauncher.launch(Intent(this, PortUnlockActivity::class.java))
            }
        } else if (locked) {
            unlocking = true
            lockLauncher.launch(Intent(this, LockActivity::class.java))
        }
    }

}
