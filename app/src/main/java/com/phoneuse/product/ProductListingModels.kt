package com.phoneuse.product

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
enum class ProductListingStatus {
    DISCOVERED,
    EXTRACTED,
    VALIDATED,
    NORMALIZED,
    MAPPED,
    FILLED,
    REVIEW_PENDING,
    DRAFT_SAVED,
    PUBLISHED_VERIFIED,
    FAILED,
}

@Serializable
data class ExtractedProduct(
    val productId: String = UUID.randomUUID().toString().take(12),
    val title: String,
    val brand: String? = null,
    val sku: String? = null,
    val category: String? = null,
    val description: String? = null,
    val price: Double? = null,
    val currency: String = "INR",
    val quantity: Int = 1,
    val sourceUrl: String? = null,
    val confidence: Float = 0.9f,
    val extractedAtMs: Long = System.currentTimeMillis(),
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class ProductListingDraft(
    val draftId: String = UUID.randomUUID().toString().take(12),
    val sourceProductId: String,
    val destinationPlatform: String,
    val destinationCategory: String? = null,
    val mappedFields: Map<String, String>,
    val status: ProductListingStatus = ProductListingStatus.DRAFT_SAVED,
    val validationErrors: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val verifiedListingId: String? = null,
)

object ProductDatabase {
    private val products = LinkedHashMap<String, ExtractedProduct>()
    private val drafts = LinkedHashMap<String, ProductListingDraft>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Synchronized
    fun addProduct(product: ExtractedProduct): ExtractedProduct {
        // Deduplicate by sku or exact title + price
        val existing = products.values.firstOrNull {
            (!product.sku.isNullOrBlank() && it.sku == product.sku) ||
                (it.title.equals(product.title, ignoreCase = true) && it.price == product.price)
        }
        if (existing != null) return existing
        products[product.productId] = product
        return product
    }

    @Synchronized
    fun listProducts(): List<ExtractedProduct> = products.values.toList()

    @Synchronized
    fun getProduct(id: String): ExtractedProduct? = products[id]

    @Synchronized
    fun saveDraft(draft: ProductListingDraft): ProductListingDraft {
        drafts[draft.draftId] = draft
        return draft
    }

    @Synchronized
    fun listDrafts(): List<ProductListingDraft> = drafts.values.toList()

    @Synchronized
    fun exportJson(): String = json.encodeToString(ListSerializer(ExtractedProduct.serializer()), products.values.toList())

    @Synchronized
    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.append("ID,Title,Brand,SKU,Category,Price,Currency,Quantity,SourceURL\n")
        for (p in products.values) {
            val safeTitle = "\"" + p.title.replace("\"", "\"\"") + "\""
            val safeBrand = "\"" + (p.brand ?: "").replace("\"", "\"\"") + "\""
            val safeSku = p.sku ?: ""
            val safeCat = "\"" + (p.category ?: "").replace("\"", "\"\"") + "\""
            val safeUrl = p.sourceUrl ?: ""
            sb.append("${p.productId},$safeTitle,$safeBrand,$safeSku,$safeCat,${p.price ?: 0.0},${p.currency},${p.quantity},$safeUrl\n")
        }
        return sb.toString()
    }

    @Synchronized
    fun clear() {
        products.clear()
        drafts.clear()
    }
}
