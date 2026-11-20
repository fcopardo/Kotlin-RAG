use std::fmt;

/// A point in the plane.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

impl Point {
    /// Creates a point.
    pub const fn new(x: f64, y: f64) -> Self {
        Point { x, y }
    }

    pub fn dist(&self, other: &Point) -> f64 {
        ((self.x - other.x).powi(2) + (self.y - other.y).powi(2)).sqrt()
    }
}

impl fmt::Display for Point {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "({}, {})", self.x, self.y)
    }
}

pub trait Shape {
    fn area(&self) -> f64;

    fn describe(&self) -> String {
        format!("shape with area {}", self.area())
    }
}

pub enum Cmd {
    Move { dx: i32, dy: i32 },
    Quit,
}

mod parse {
    use super::Point;

    pub(crate) fn point(s: &str) -> Option<Point> {
        let (a, b) = s.split_once(',')?;
        Some(Point::new(a.trim().parse().ok()?, b.trim().parse().ok()?))
    }

    #[cfg(test)]
    mod tests {
        #[test]
        fn parses() {
            assert_eq!(super::point("1,2"), Some(super::Point::new(1.0, 2.0)));
        }
    }
}

impl<T: fmt::Debug> Wrapper<T>
where
    T: Clone,
{
    fn show(&self) -> String {
        let r = match self.0.clone() {
            v => format!("{:?}", v),
        };
        r
    }
}

pub struct Wrapper<T>(pub T);

fn apply<F: Fn(i32) -> i32>(f: F, x: i32) -> i32 {
    let add = |a: i32| -> i32 { a + x };
    f(add(x))
}
