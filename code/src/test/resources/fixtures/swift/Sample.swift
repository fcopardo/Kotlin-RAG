import Foundation

/// A point in the plane.
public struct Point: Equatable {
    public var x: Double
    public var y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }

    /// Distance to another point.
    public func distance(to other: Point) -> Double {
        let dx = x - other.x, dy = y - other.y
        return (dx * dx + dy * dy).squareRoot()
    }

    public var length: Double {
        get { distance(to: .zero) }
    }

    static func + (lhs: Point, rhs: Point) -> Point {
        Point(x: lhs.x + rhs.x, y: lhs.y + rhs.y)
    }
}

extension Point: CustomStringConvertible {
    public var description: String { "(\(x), \(y))" }

    static let zero = Point(x: 0, y: 0)
}

protocol Shape {
    func area() -> Double
}

final class Circle: Shape {
    let r: Double

    init?(r: Double) {
        guard r >= 0 else { return nil }
        self.r = r
    }

    deinit {
        print("bye {")
    }

    func area() -> Double { .pi * r * r }

    @discardableResult
    class func unit() -> Circle {
        Circle(r: 1)!
    }
}

enum Direction: String {
    case north, south

    var opposite: Direction {
        switch self {
        case .north: return .south
        case .south: return .north
        }
    }

    mutating func flip() {
        self = opposite
    }
}

actor Counter {
    private var n = 0

    func increment() -> Int {
        n += 1
        return n
    }
}

func makeCounter() -> () -> Int {
    var count = 0
    return {
        count += 1
        return count
    }
}
