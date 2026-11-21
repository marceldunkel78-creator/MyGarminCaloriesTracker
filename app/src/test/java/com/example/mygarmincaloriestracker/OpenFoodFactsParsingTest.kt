package com.example.mygarmincaloriestracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OpenFoodFactsParsingTest {
    private fun fields(
        name: String? = null, brand: String? = null, kcal: String? = null,
        kj: String? = null, quantity: String? = null, size: String? = null
    ) = OpenFoodFactsFields(name, brand, kcal, kj, quantity, size)

    @Test fun validatesGtinLengthsDigitsAndCheckDigit() {
        listOf("96385074", "036000291452", "4006381333931", "10012345000017").forEach {
            assertTrue(it, isValidGtin(it))
        }
        listOf("", "4006381333932", "4006381333931/../", " 4006381333931", "１２３４５６７８", "123456789").forEach {
            assertFalse(it, isValidGtin(it))
        }
    }

    @Test fun mapsFieldsAndPrefersKcalIncludingZero() {
        val product = productFromFields(fields(" Food ", " Brand ", "0", "418.4", "30.0", "30 g"))
        assertEquals("Food", product.name)
        assertEquals("Brand", product.brand)
        assertEquals(0.0, product.kcalPer100!!, 0.0)
        assertEquals(30.0, product.servingGrams!!, 0.0)
        assertEquals("30 g", product.servingLabel)
    }

    @Test fun convertsKilojoulesAndDoesNotInventMissingValues() {
        assertEquals(100.0, productFromFields(fields(kj = "418.4")).kcalPer100!!, 0.00001)
        assertEquals(30.0, productFromFields(fields(quantity = "3e1", size = "30 g")).servingGrams!!, 0.0)
        val missing = productFromFields(fields())
        assertEquals("", missing.name)
        assertNull(missing.brand)
        assertNull(missing.kcalPer100)
        assertNull(missing.servingGrams)
        assertNull(missing.servingLabel)
        assertNull(productFromFields(fields(kcal = "NaN", kj = "-1")).kcalPer100)
        assertNull(productFromFields(fields(kcal = "1e9999", kj = "Infinity")).kcalPer100)
    }

    @Test fun handlesDecimalsIndependentlyOfDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val product = productFromFields(fields(kcal = "123.5", quantity = "12,5", size = "12,5 g"))
            assertEquals(123.5, product.kcalPer100!!, 0.0)
            assertEquals(12.5, product.servingGrams!!, 0.0)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun servingRequiresUnambiguousGramLabel() {
        assertEquals(25.0, productFromFields(fields(size = "25 grams")).servingGrams!!, 0.0)
        listOf("100 ml", "1 bar (30 g)", "2 x 30 g", "30 g / 100 ml", "1 serving", "0 g").forEach {
            assertNull(it, productFromFields(fields(quantity = "30", size = it)).servingGrams)
        }
        assertNull(productFromFields(fields(quantity = "30")).servingGrams)
        assertNull(productFromFields(fields(quantity = "50", size = "30 g")).servingGrams)
        assertEquals(30.0, productFromFields(fields(quantity = "bad", size = "30g")).servingGrams!!, 0.0)
    }
}
