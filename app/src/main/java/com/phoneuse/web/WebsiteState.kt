package com.phoneuse.web

import com.phoneuse.screen.CompressedScreen
import com.phoneuse.screen.UiNode
import java.net.URI

enum class WebPageType {
    HOME,
    CATEGORY,
    PRODUCT_LISTING,
    PRODUCT_DETAIL,
    FORM_ENTRY,
    CART,
    CHECKOUT,
    LOGIN,
    ERROR_OR_BLOCKED,
    GENERAL_CONTENT,
}

data class WebControl(
    val index: Int,
    val label: String,
    val type: String, // button, link, input, select, checkbox
    val isActionable: Boolean,
)

data class WebsiteState(
    val url: String?,
    val domain: String?,
    val title: String?,
    val pageType: WebPageType,
    val visibleControls: List<WebControl>,
    val mainContentSummary: String,
    val scrollContinues: Boolean,
    val hasActiveModal: Boolean,
    val isLoading: Boolean,
    val evidence: String,
    val confidence: Float,
    val screenFingerprint: Int,
) {
    companion object {
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.brave.browser",
            "com.microsoft.emmx",
        )

        fun isBrowserPackage(pkg: String?): Boolean = pkg in BROWSER_PACKAGES

        fun analyze(screen: CompressedScreen, fingerprint: Int = screen.text.hashCode()): WebsiteState {
            val isBrowser = isBrowserPackage(screen.packageName)
            val fullText = screen.text.lowercase()

            // 1. Extract potential URL / Domain from address bar or headers
            var foundUrl: String? = null
            var foundDomain: String? = null
            for ((_, label) in screen.fullLabels) {
                val trimmed = label.trim()
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                    (trimmed.contains(".") && !trimmed.contains(" ") && (trimmed.endsWith(".com") || trimmed.endsWith(".in") || trimmed.endsWith(".org") || trimmed.endsWith(".net")))
                ) {
                    val full = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
                    foundUrl = full
                    foundDomain = runCatching { URI(full).host }.getOrNull() ?: trimmed.substringBefore('/')
                    break
                }
            }

            // 2. Classify page type based on visible accessibility cues
            val pageType = when {
                fullText.contains("404 not found") || fullText.contains("access denied") || fullText.contains("blocked") -> WebPageType.ERROR_OR_BLOCKED
                fullText.contains("sign in") && fullText.contains("password") -> WebPageType.LOGIN
                fullText.contains("checkout") || fullText.contains("place order") -> WebPageType.CHECKOUT
                fullText.contains("cart") || fullText.contains("장바구니") || fullText.contains("shopping bag") -> WebPageType.CART
                (fullText.contains("add to cart") || fullText.contains("buy now") || fullText.contains("in stock") || fullText.contains("₹") || fullText.contains("$")) &&
                    (fullText.contains("specifications") || fullText.contains("reviews") || fullText.contains("sku")) -> WebPageType.PRODUCT_DETAIL
                (fullText.contains("filters") || fullText.contains("sort by") || fullText.contains("results for") || fullText.contains("categories")) &&
                    (fullText.contains("items") || fullText.contains("products")) -> WebPageType.PRODUCT_LISTING
                screen.hasDialogOrPopup -> WebPageType.FORM_ENTRY
                else -> if (isBrowser) WebPageType.GENERAL_CONTENT else WebPageType.HOME
            }

            // 3. Collect visible controls
            val controls = mutableListOf<WebControl>()
            screen.elements.forEach { (idx, node) ->
                val label = screen.labels[idx].orEmpty()
                if (label.isNotBlank() && (node.clickable || node.editable || node.checkable)) {
                    val type = when {
                        node.editable -> "input"
                        node.checkable -> "checkbox"
                        node.clickable -> "button"
                        else -> "element"
                    }
                    controls += WebControl(idx, label, type, node.isActionable)
                }
            }

            val title = screen.fullLabels.values.firstOrNull { it.length in 8..80 } ?: screen.packageName.orEmpty()
            val summary = screen.labels.values.take(6).joinToString(" | ")

            return WebsiteState(
                url = foundUrl,
                domain = foundDomain,
                title = title,
                pageType = pageType,
                visibleControls = controls.take(40),
                mainContentSummary = summary,
                scrollContinues = screen.listContinues,
                hasActiveModal = screen.hasDialogOrPopup,
                isLoading = screen.hasLoadingIndicator,
                evidence = "Detected pageType=$pageType controls=${controls.size} url=${foundUrl ?: "in-app"}",
                confidence = if (isBrowser) 0.95f else 0.85f,
                screenFingerprint = fingerprint,
            )
        }
    }
}
