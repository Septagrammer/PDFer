package com.pavlo.pdfer.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredPageSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun roundTripsTextAndImageElements() {
        val page = StoredPage(
            listOf(
                StoredElement(text = "hello world"),
                StoredElement(image = "/data/user/0/app/cache/ocr/x/0_fast_e1_img0.png", w = 640, h = 480),
            )
        )
        val encoded = json.encodeToString(StoredPage.serializer(), page)
        val decoded = json.decodeFromString(StoredPage.serializer(), encoded)

        assertEquals(page, decoded)
        assertEquals("hello world", decoded.elements[0].text)
        assertEquals(null, decoded.elements[0].image)
        assertEquals(640, decoded.elements[1].w)
        assertEquals(480, decoded.elements[1].h)
        assertEquals(null, decoded.elements[1].text)
    }

    @Test fun toleratesUnknownKeys() {
        val decoded = json.decodeFromString(
            StoredPage.serializer(),
            """{"elements":[{"text":"x","futureField":123}]}"""
        )
        assertEquals("x", decoded.elements.single().text)
    }
}
