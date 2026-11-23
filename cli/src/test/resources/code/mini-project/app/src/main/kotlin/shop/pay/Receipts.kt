package shop.pay

/** Formats cents as "12.34". */
fun format(amount: Money): String = "%d.%02d".format(amount.cents / 100, amount.cents % 100)

/** The receipt's lines: one per item, then the total. */
fun receipt(items: List<Pair<String, Money>>): List<String> {
    val lines = items.map { (name, price) -> "$name  ${format(price)}" }
    val total = items.fold(0L) { sum, (_, price) -> sum + price.cents }
    return lines + "TOTAL  ${format(Money(total))}"
}
