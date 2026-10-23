package dev.multiclone.fixture.feature

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class FeatureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { textSize = 22f; setPadding(30, 60, 30, 30)
            text = "Dynamic feature loaded\nPackage: $packageName\nCount: ${getSharedPreferences("state", 0).getInt("count", 0)}"
        })
    }
}
