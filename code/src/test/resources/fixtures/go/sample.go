package geo

import (
	"fmt"
	"math"
)

// Point is a point in the plane.
type Point struct {
	X, Y float64
}

// Dist returns the distance to q.
func (p Point) Dist(q Point) float64 {
	return math.Hypot(q.X-p.X, q.Y-p.Y)
}

func (p *Point) Scale(k float64) {
	p.X *= k
	p.Y *= k
}

type Shape interface {
	Area() float64
	Perimeter() float64
}

type Stack[T any] struct {
	items []T
}

func (s *Stack[T]) Push(v T) {
	s.items = append(s.items, v)
}

func Map[T, U any](xs []T, f func(T) U) []U {
	out := make([]U, 0, len(xs))
	for _, x := range xs {
		out = append(out, f(x))
	}
	return out
}

var origin = Point{X: 0, Y: 0}

func main() {
	defer func() {
		if r := recover(); r != nil {
			fmt.Println("recovered {", r)
		}
	}()
	s := &Stack[int]{}
	s.Push(1)
	fmt.Printf("%v\n", Map([]int{1, 2}, func(x int) int { return x * 2 }))
}
