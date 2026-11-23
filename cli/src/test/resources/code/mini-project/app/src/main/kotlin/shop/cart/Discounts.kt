package shop.cart

import shop.pay.Money

/** Discount rules applied at checkout. */
object Discounts {
    /** Ten percent off orders of 100 or more. */
    fun apply(total: Money): Money =
        if (total.cents >= 10_000) total - percentOf(total, 10) else total
}

/** [percent] percent of [amount], rounded down to the cent. */
fun percentOf(amount: Money, percent: Int): Money = Money(amount.cents * percent / 100)
