package shop.cart

import shop.pay.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class CartTest {
    @Test
    fun `adding a product twice raises its quantity`() {
        val cart = Cart("ana")
        cart.add("tea", Money(250))
        cart.add("tea", Money(250), 2)
        assertEquals(Money(750), cart.total())
    }
}
