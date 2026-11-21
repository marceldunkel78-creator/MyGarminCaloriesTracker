package com.example.mygarmincaloriestracker

/** Values returned by Open Food Facts, not yet confirmed or saved by the user. */
data class Product(
    val name: String,
    val brand: String?,
    val kcalPer100: Double?,
    val servingGrams: Double?,
    val servingLabel: String?
)

sealed class OpenFoodFactsResult {
    data class Success(val product: Product) : OpenFoodFactsResult()
    object NotFound : OpenFoodFactsResult()
    data class Error(val reason: Reason, val httpStatus: Int? = null) : OpenFoodFactsResult()

    enum class Reason { INVALID_BARCODE, NETWORK, HTTP, INVALID_RESPONSE, RESPONSE_TOO_LARGE }
}

/** Validates an ASCII GTIN including its GS1 check digit; leading zeroes are significant. */
internal fun isValidGtin(value: String): Boolean {
    if (value.length !in setOf(8, 12, 13, 14) || value.any { it !in '0'..'9' }) return false
    val sum = value.dropLast(1).reversed().mapIndexed { index, digit ->
        (digit - '0') * if (index % 2 == 0) 3 else 1
    }.sum()
    return (10 - sum % 10) % 10 == value.last() - '0'
}

/** JSON-independent inputs: the Android JSON adapter extracts these as strings. */
internal data class OpenFoodFactsFields(
    val name: String?,
    val brand: String?,
    val kcal: String?,
    val kilojoules: String?,
    val servingQuantity: String?,
    val servingSize: String?
)

// Accept decimal and scientific notation without locale-dependent parsing or grouping.
private val decimal = Regex("[0-9]+(?:[.,][0-9]+)?(?:[eE][+-]?[0-9]+)?")
private fun nonNegativeNumber(raw: String?): Double? {
    val value = raw?.trim() ?: return null
    if (!decimal.matches(value)) return null
    return value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
}

private val gramsLabel = Regex("^([0-9]+(?:[.,][0-9]+)?)\\s*(?:g|gram|grams)$", RegexOption.IGNORE_CASE)

/** Only interpret a simple standalone gram label, not volumes or multi-portion labels. */
internal fun productFromFields(fields: OpenFoodFactsFields): Product {
    val label = fields.servingSize?.trim()?.takeIf { it.isNotEmpty() }
    val labelGrams = label?.let { gramsLabel.matchEntire(it) }
        ?.groupValues?.get(1)?.let(::nonNegativeNumber)?.takeIf { it > 0.0 }
    val quantity = nonNegativeNumber(fields.servingQuantity)?.takeIf { it > 0.0 }
    // serving_quantity alone has no unit. Require an explicit simple gram label;
    // conflicting values are ambiguous rather than guessing which one is correct.
    val servingGrams = when {
        labelGrams == null -> null
        quantity == null -> labelGrams
        kotlin.math.abs(quantity - labelGrams) <= 0.000001 -> quantity
        else -> null
    }
    val kcal = nonNegativeNumber(fields.kcal)
        ?: nonNegativeNumber(fields.kilojoules)?.div(4.184)
    return Product(
        name = fields.name?.trim().orEmpty(),
        brand = fields.brand?.trim()?.takeIf { it.isNotEmpty() },
        kcalPer100 = kcal,
        servingGrams = servingGrams,
        servingLabel = label
    )
}
