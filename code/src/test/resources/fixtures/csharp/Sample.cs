using System;
using System.Collections.Generic;

namespace Rag.Sample
{
    /// <summary>A stack of items.</summary>
    public class Stack<T> where T : notnull
    {
        private readonly List<T> items = new();

        public int Count => items.Count;

        public Stack(IEnumerable<T> initial)
        {
            items.AddRange(initial);
        }

        /// <summary>Pushes an item.</summary>
        public void Push(T item) => items.Add(item);

        [Obsolete("use TryPop")]
        public T Pop()
        {
            if (items.Count == 0)
            {
                throw new InvalidOperationException($"empty {nameof(Stack<T>)}");
            }
            var last = items[^1];
            items.RemoveAt(items.Count - 1);
            return last;
        }

        public bool TryPop(out T value)
        {
            value = default!;
            return false;
        }

        public T this[int i]
        {
            get { return items[i]; }
            set { items[i] = value; }
        }

        public static Stack<T> operator +(Stack<T> a, T b)
        {
            a.Push(b);
            return a;
        }

        public override string ToString() =>
            string.Join(", ", items);
    }

    public record Point(double X, double Y)
    {
        public double Length() => Math.Sqrt(X * X + Y * Y);
    }

    public struct Size
    {
        public int W { get; init; }

        public int Area()
        {
            Func<int, int> twice = x => { return x * 2; };
            return W * W;
        }
    }

    internal interface IShape
    {
        double Area();
    }

    public enum Color { Red, Green }
}
