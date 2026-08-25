package com.vikash.voicescribe.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vikash.voicescribe.billing.BillingManager

/**
 * One-time-unlock paywall. Free tier keeps unlimited recording, transcription,
 * and text sharing — this gates only the document exports (and future pro
 * features), matching the volume-first monetization plan.
 */
@Composable
fun PaywallDialog(
    billing: BillingManager,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val price by billing.price.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.WorkspacePremium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("Unlock exports") },
        text = {
            Column {
                PaywallPoint("Export transcripts as PDF")
                PaywallPoint("Export transcripts as Word (.docx)")
                PaywallPoint("All future Pro features included")
                Spacer(Modifier.height(10.dp))
                Text(
                    "One-time purchase — yours forever, no subscription. " +
                        "Recording, transcription, and text sharing stay free and unlimited.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val activity = context as? Activity
                val launched = activity != null && billing.launchPurchase(activity)
                if (launched) {
                    onDismiss()
                } else if (billing.debugUnlockAvailable) {
                    billing.debugGrant()
                    Toast.makeText(context, "Unlocked (debug build)", Toast.LENGTH_SHORT).show()
                    onDismiss()
                } else {
                    Toast.makeText(
                        context,
                        "Google Play is unavailable right now — try again later.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }) {
                Text("Unlock ${price ?: BillingManager.FALLBACK_PRICE}")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    billing.refreshEntitlement()
                    onDismiss()
                }) { Text("Restore") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        },
    )
}

@Composable
private fun PaywallPoint(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp).height(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
