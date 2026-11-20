package xyz.sabok.umtomi.sync

import android.content.Context

/** One identity for bookshelf and Umtomi cloud data. Never silently merge different legacy accounts. */
internal object CloudAccount {
    fun preferences(context: Context) = context.getSharedPreferences("umtomi-cloud-account", Context.MODE_PRIVATE)
    fun account(context: Context): String? {
        val prefs = preferences(context)
        if (!prefs.getBoolean("migrated", false)) {
            val cloud = context.getSharedPreferences("umtomi-drive", Context.MODE_PRIVATE).getString("account", null)
            val shelf = context.getSharedPreferences("shared-bookshelf", Context.MODE_PRIVATE).getString("account", null)
            val conflict = cloud != null && shelf != null && !cloud.equals(shelf, ignoreCase = true)
            prefs.edit().putBoolean("migrated", true).putBoolean("conflict", conflict)
                .putString("account", if (conflict) null else cloud ?: shelf).commit()
        }
        return prefs.getString("account", null)
    }
    fun connect(context: Context, account: String) {
        preferences(context).edit().putBoolean("migrated", true).putBoolean("conflict", false).putString("account", account).commit()
        context.getSharedPreferences("umtomi-drive", Context.MODE_PRIVATE).edit().putString("account", account).apply()
        context.getSharedPreferences("shared-bookshelf", Context.MODE_PRIVATE).edit().putString("account", account).putBoolean("enabled", true).apply()
    }
    fun disconnect(context: Context) {
        preferences(context).edit().remove("account").putBoolean("migrated", true).putBoolean("conflict", false).commit()
        context.getSharedPreferences("umtomi-drive", Context.MODE_PRIVATE).edit().remove("account").apply()
        context.getSharedPreferences("shared-bookshelf", Context.MODE_PRIVATE).edit().remove("account").putBoolean("enabled", false).apply()
    }
}
