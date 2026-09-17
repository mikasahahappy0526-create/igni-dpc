package app.igni.dpc

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import app.igni.dpc.policy.PolicyApplier

/**
 * Android 10+ (`ADMIN_POLICY_COMPLIANCE`) and older `PROVISIONING_SUCCESSFUL`.
 * Applies the hide allowlist automatically — no user interaction required — then
 * returns `RESULT_OK` so Setup Wizard can complete.
 */
class PolicyComplianceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provisioning)

        val result = PolicyApplier(this).apply()
        Log.i(TAG, "Policy compliance apply success=${result.success} hidden=${result.hiddenCount}")
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val TAG = "IgniPolicyCompliance"
    }
}
