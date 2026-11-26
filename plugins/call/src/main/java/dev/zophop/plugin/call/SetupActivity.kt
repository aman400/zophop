package dev.zophop.plugin.call

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions =
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.SEND_SMS,
            )

        val ungranted = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (ungranted.isNotEmpty()) {
            requestPermissions(ungranted.toTypedArray(), 101)
        } else {
            Toast.makeText(this, "Call Plugin permissions already granted!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Toast.makeText(this, "Call Plugin permissions updated", Toast.LENGTH_SHORT).show()
        finish()
    }
}
