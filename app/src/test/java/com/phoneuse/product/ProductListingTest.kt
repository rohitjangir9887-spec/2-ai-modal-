package com.phoneuse.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProductListingTest {

    @Before
    fun setUp() {
        ProductDatabase.clear()
    }

    @Test
    fun testAddAndDeduplicateProduct() {
        val p1 = ExtractedProduct(
            productId = "prod-1",
            title = "Noise Cancelling Headphones",
            brand = "Sony",
            sku = "WH-1000XM5",
            category = "Electronics",
            price = 24999.0,
            currency = "INR",
        )
        val added1 = ProductDatabase.addProduct(p1)
        assertEquals("prod-1", added1.productId)

        // Duplicate with same SKU should return existing
        val p2 = ExtractedProduct(
            productId = "prod-2",
            title = "Noise Cancelling Headphones V2",
            sku = "WH-1000XM5",
            price = 24999.0,
        )
        val added2 = ProductDatabase.addProduct(p2)
        assertEquals("prod-1", added2.productId)
        assertEquals(1, ProductDatabase.listProducts().size)
    }

    @Test
    fun testExportCsvAndJson() {
        val p1 = ExtractedProduct(
            productId = "p-101",
            title = "Wireless Mouse",
            brand = "Logitech",
            sku = "M220",
            category = "Accessories",
            price = 999.0,
            currency = "INR",
            quantity = 2,
            sourceUrl = "https://example.com/mouse",
        )
        ProductDatabase.addProduct(p1)

        val csv = ProductDatabase.exportCsv()
        assertTrue(csv.contains("Wireless Mouse"))
        assertTrue(csv.contains("Logitech"))
        assertTrue(csv.contains("999.0"))

        val json = ProductDatabase.exportJson()
        assertTrue(json.contains("Wireless Mouse"))
        assertTrue(json.contains("p-101"))
    }

    @Test
    fun testDraftLifecycle() {
        val draft = ProductListingDraft(
            draftId = "draft-1",
            sourceProductId = "p-101",
            destinationPlatform = "Amazon India",
            mappedFields = mapOf("title" to "Wireless Mouse", "standard_price" to "999"),
            status = ProductListingStatus.DRAFT_SAVED,
        )
        ProductDatabase.saveDraft(draft)

        val drafts = ProductDatabase.listDrafts()
        assertEquals(1, drafts.size)
        assertEquals("Amazon India", drafts[0].destinationPlatform)
        assertEquals(ProductListingStatus.DRAFT_SAVED, drafts[0].status)
    }
}
