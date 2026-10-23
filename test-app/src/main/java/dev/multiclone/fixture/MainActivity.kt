package dev.multiclone.fixture

import android.app.Activity
import android.os.Bundle
import android.content.ContentValues
import android.net.Uri
import android.widget.*

/** Apache-2.0 fixture: a local mock session, never real authentication. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("state", MODE_PRIVATE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        val state = TextView(this).apply { textSize = 22f }
        val account = EditText(this).apply { hint = "Local mock account"; setSingleLine(); setText(prefs.getString("account", "")) }
        fun refresh() {
            val uri = UriHelper.selfUri()
            val providerValue = contentResolver.query(uri, null, null, null, null)?.use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            state.text = "Package: $packageName\nCount: ${prefs.getInt("count", 0)}\nAccount: ${prefs.getString("account", "signed out")}\nProvider: $providerValue"
        }
        layout.addView(TextView(this).apply { text = "MultiClone isolation fixture"; textSize = 26f })
        layout.addView(state); layout.addView(account)
        layout.addView(Button(this).apply { text = "Increment"; isAllCaps = false; setOnClickListener { prefs.edit().putInt("count", prefs.getInt("count", 0) + 1).apply(); refresh() } })
        layout.addView(Button(this).apply { text = "Save mock login"; isAllCaps = false; setOnClickListener { prefs.edit().putString("account", account.text.toString()).apply(); refresh() } })
        layout.addView(Button(this).apply { text = "Sign out"; isAllCaps = false; setOnClickListener { prefs.edit().remove("account").apply(); refresh() } })
        layout.addView(Button(this).apply { text = "Open feature"; isAllCaps = false; setOnClickListener {
            try { startActivity(android.content.Intent().setClassName(packageName, "dev.multiclone.fixture.feature.FeatureActivity")) }
            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this@MainActivity, "Feature is not part of this APK set", Toast.LENGTH_SHORT).show() }
        } })
        setContentView(ScrollView(this).apply { addView(layout) }); refresh()
    }
}
