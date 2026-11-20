package rag.sample

import kotlin.math.sqrt

/** A point in the plane. */
data class Point(val x: Double, val y: Double) {
    /** Distance to [other]. */
    fun distanceTo(other: Point): Double {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }

    operator fun plus(o: Point) = Point(x + o.x, y + o.y)

    companion object {
        val ORIGIN = Point(0.0, 0.0)

        fun of(s: String): Point {
            val (a, b) = s.split(",").map { it.trim().toDouble() }
            return Point(a, b)
        }
    }
}

sealed interface Shape {
    fun area(): Double

    fun describe(): String = "${this::class.simpleName} with area ${area()}"

    class Circle(val r: Double) : Shape {
        override fun area() = Math.PI * r * r
    }

    class Rect(val w: Double, val h: Double) : Shape {
        init {
            require(w >= 0 && h >= 0) { "negative size: {$w, $h}" }
        }

        constructor(side: Double) : this(side, side)

        override fun area(): Double {
            fun half(v: Double) = v / 2
            return half(w) * 2 * h
        }
    }
}

enum class Color(val rgb: Int) {
    RED(0xFF0000) {
        override fun label() = "red"
    },
    GREEN(0x00FF00) {
        override fun label() = "green"
    };

    abstract fun label(): String
}

@JvmInline
value class Id(val value: Long)

object Registry {
    private val items = mutableMapOf<Id, Point>()

    @Synchronized
    fun register(id: Id, p: Point) {
        items[id] = p
    }

    val size: Int
        get() {
            return items.size
        }
}

fun <T : Comparable<T>> List<T>.maxOrNull(): T? =
    if (isEmpty()) null
    else reduce { a, b -> if (a >= b) a else b }

private fun `parse a list`(text: String): List<Point> = text.lines()
    .filter { it.isNotBlank() }
    .map { Point.of(it) }

fun main() {
    val handler = object : Runnable {
        override fun run() {
            println("running {")
        }
    }
    handler.run()
}
