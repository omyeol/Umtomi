package xyz.sabok.umtomi.ui

/** Retain old navigation targets while using the single cloud account screen. */
class SharedShelfActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(android.content.Intent(this, DriveSyncActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
