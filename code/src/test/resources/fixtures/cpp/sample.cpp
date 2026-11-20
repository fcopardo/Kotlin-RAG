#include <cmath>
#include <string>
#include "geometry.h"

namespace geo {

/// A point in the plane.
struct Point {
    double x = 0, y = 0;

    Point(double x, double y) : x(x), y(y) {}

    [[nodiscard]] double length() const;

    bool operator==(const Point& o) const {
        return x == o.x && y == o.y;
    }
};

double Point::length() const {
    return std::sqrt(x * x + y * y);
}

class Shape {
public:
    virtual ~Shape() = default;
    virtual double area() const = 0;
};

class Circle final : public Shape {
public:
    explicit Circle(double r) : r_(r) {}
    ~Circle() override {}

    double area() const override {
        auto square = [](double v) { return v * v; };
        return 3.14159 * square(r_);
    }

private:
    double r_;
};

template <typename T>
T clamp(T v, T lo, T hi) {
    if (v < lo) {
        return lo;
    }
    return v > hi ? hi : v;
}

enum class Color { Red, Green };

}  // namespace geo

extern "C" {
int geo_version(void) {
    return 2;
}
}

static const char* names[] = {"a{", "b}"};

int main(int argc, char** argv) {
    geo::Point p{1.0, 2.0};
    for (int i = 0; i < argc; ++i) {
        std::string s(argv[i]);
    }
    return p.length() > 0 ? 0 : 1;
}
