package com.example.screentofacedistance

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

class AlertActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make activity show over lock screen and wake up device
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alert)

        // Handle back press properly (gesture + button)
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }

        findViewById<Button>(R.id.btnDismiss).setOnClickListener {
            finish()
        }
    }
}
