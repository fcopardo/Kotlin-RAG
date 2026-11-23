package shop.cart

import shop.pay.Money

/** A shopping cart: lines of products with quantities. */
class Cart(private val owner: String) {
    private val lines = mutableListOf<Line>()

    /** One product in the cart. */
    data class Line(val sku: String, val unitPrice: Money, val quantity: Int) {
        fun subtotal(): Money = unitPrice * quantity
    }

    /** Adds [quantity] of [sku]; adding a product again raises its quantity. */
    fun add(sku: String, unitPrice: Money, quantity: Int = 1) {
        require(quantity > 0) { "quantity must be positive" }
        val existing = lines.indexOfFirst { it.sku == sku }

        if (existing >= 0) {
            lines[existing] = lines[existing].copy(quantity = lines[existing].quantity + quantity)
        } else {
            lines += Line(sku, unitPrice, quantity)
        }
    }

    fun total(): Money = lines.fold(Money.ZERO) { sum, line -> sum + line.subtotal() }
}
