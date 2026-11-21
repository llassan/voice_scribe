package com.vikash.voicescribe.ads

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.vikash.voicescribe.BuildConfig

/**
 * Advertising, kept to the smallest thing that can earn anything: one banner on
 * the recordings list and nothing anywhere else.
 *
 * Deliberately absent:
 *  - no banner on the transcript screen, where people read and play audio;
 *  - no interstitial when transcription finishes, which is the exact moment the
 *    user wants their result;
 *  - no rewarded ads, because gating transcription would contradict the one
 *    promise the store listing is built on ("unlimited and free — forever").
 *
 * The advertising ID permissions are removed in the manifest, so everything
 * served here is non-personalised.
 */
private const val TAG = "VoiceScribeAds"

object Ads {
    fun init(context: Context) {
        // VoiceScribe is rated Everyone / PEGI 3 on the store, so the ads it serves
        // have to suit that rating rather than the default, which allows more. Play
        // treats a mismatch here as the app's problem, not the network's. G narrows
        // the available inventory, which costs fill — worth far less than the rating
        // staying defensible.
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_G)
                .build()
        )
        // Initialising touches disk and network, so it stays off the main thread.
        val app = context.applicationContext
        Thread { MobileAds.initialize(app) {} }.start()
    }
}

/**
 * A banner sized to the current screen width. Its height is reserved before the
 * ad arrives so the record button underneath never jumps while the user is
 * reaching for it.
 */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val size = remember(widthDp) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(size.height.dp)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            // Both the modifier and the layout params are needed. Without them the
            // AdView wraps content, measures to zero height inside the Box and draws
            // nothing at all — the banner silently occupies reserved space while
            // showing an empty grey strip, with no error anywhere in the log.
            modifier = Modifier.fillMaxWidth().height(size.height.dp),
            factory = { ctx ->
                AdView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setAdSize(size)
                    adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
                    adListener = object : AdListener() {
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            // An unfilled banner is normal and harmless; it just
                            // leaves the strip empty. Logged because a permanent
                            // failure is otherwise completely invisible.
                            Log.w(TAG, "Banner failed: ${'$'}{error.code} ${'$'}{error.message}")
                        }

                        override fun onAdLoaded() {
                            Log.i(TAG, "Banner loaded")
                        }
                    }
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
