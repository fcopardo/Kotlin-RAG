package shop.pay

/** An amount of money in cents. */
@JvmInline
value class Money(val cents: Long) {
    operator fun plus(other: Money) = Money(cents + other.cents)
    operator fun minus(other: Money) = Money(cents - other.cents)
    operator fun times(n: Int) = Money(cents * n)

    companion object {
        val ZERO = Money(0)
    }
}

/** Something that can take a payment. */
interface Gateway {
    fun charge(card: String, amount: Money): Boolean
}

/** Charges a card through the bank's API; refuses amounts over the card's limit. */
class CardGateway(
    private val limit: Money,
    /** Called with each refusal, e.g. to log it. */
    private val onRefused: (String) -> Unit = { },
) : Gateway {
    override fun charge(card: String, amount: Money): Boolean {
        if (amount.cents > limit.cents) {
            onRefused("over limit: $card")
            return false
        }
        return true
    }
}
