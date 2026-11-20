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

package xyz.sabok.umtomi.ui.fragment

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.ui.FavoritesActivity

class ManageFavoritesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.manage_favorites_preferences, rootKey)
        for ((key, action) in listOf("backup" to "export", "restore" to "import")) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), FavoritesActivity::class.java).putExtra("action", action))
                true
            }
        }
    }
}
