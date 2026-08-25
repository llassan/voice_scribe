package com.vikash.voicescribe.billing

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "BillingManager"

/**
 * One-time "Pro" unlock via Google Play Billing.
 *
 * Entitlement is cached in prefs for instant/offline startup, and re-verified
 * against Play's purchase records (queryPurchases) whenever billing connects —
 * Play is the source of truth, and that query is also the "restore purchases"
 * path on a new device.
 *
 * On debuggable builds where Play has no product (sideloaded dev installs),
 * [debugUnlockAvailable]/[debugGrant] provide a test unlock so the gating can
 * be exercised end-to-end before the Play Console listing exists.
 */
class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_ID = "voicescribe_pro"
        const val FALLBACK_PRICE = "$7.99"
    }

    private val prefs = context.getSharedPreferences("billing", Context.MODE_PRIVATE)

    private val _isPro = MutableStateFlow(prefs.getBoolean("pro", false))
    val isPro: StateFlow<Boolean> = _isPro

    /** Localized store price once Play returns the product; null until then. */
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price

    private var productDetails: ProductDetails? = null

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    init {
        connect()
    }

    private fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    refreshEntitlement()
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // Reconnected lazily on the next purchase attempt.
            }
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = list.firstOrNull()
                _price.value = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
            }
        }
    }

    /** Re-checks ownership with Play; also the restore-purchases path. */
    fun refreshEntitlement() {
        if (!client.isReady) return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val owned = purchases.any {
                PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            if (owned) {
                grant()
                purchases.filter { !it.isAcknowledged }.forEach { acknowledge(it) }
            }
        }
    }

    /** Starts the Play purchase sheet. False when Play/product is unavailable. */
    fun launchPurchase(activity: Activity): Boolean {
        val details = productDetails ?: return false
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        return client.launchBillingFlow(activity, flow).responseCode ==
            BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) return
        purchases.orEmpty().forEach { purchase ->
            if (PRODUCT_ID in purchase.products &&
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            ) {
                grant()
                if (!purchase.isAcknowledged) acknowledge(purchase)
            }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        ) { }
    }

    private fun grant() {
        prefs.edit().putBoolean("pro", true).apply()
        _isPro.value = true
    }

    /** True only for debuggable builds where the store product isn't available. */
    val debugUnlockAvailable: Boolean
        get() = productDetails == null &&
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun debugGrant() {
        if (debugUnlockAvailable) grant()
    }
}
