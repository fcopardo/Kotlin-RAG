package rag.sample;

import java.util.ArrayList;
import java.util.List;

/** Shapes and their areas. */
public final class Shapes {
    private final List<Shape> shapes = new ArrayList<>();

    /** Adds a shape; returns this for chaining. */
    public Shapes add(Shape s) {
        shapes.add(s);
        return this;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("{");
        for (Shape s : shapes) {
            if (b.length() > 1) {
                b.append(", ");
            }
            b.append(s.describe());
        }
        return b.append("}").toString();
    }

    public static <T extends Shape> double total(List<T> items) {
        double sum = 0;
        for (T t : items) sum += t.area();
        return sum;
    }

    interface Shape {
        double area();

        default String describe() {
            return getClass().getSimpleName() + " " + area();
        }
    }

    record Circle(double r) implements Shape {
        Circle {
            if (r < 0) throw new IllegalArgumentException("r < 0: {" + r + "}");
        }

        public double area() {
            return Math.PI * r * r;
        }
    }

    static class Rect implements Shape {
        private final double w, h;

        Rect(double w, double h) {
            this.w = w;
            this.h = h;
        }

        @Override
        public double area() {
            Runnable check = () -> {
                if (w < 0) throw new IllegalStateException();
            };
            check.run();
            return w * h;
        }
    }

    enum Unit {
        CM(1) {
            @Override String label() { return "cm"; }
        },
        INCH(2.54) {
            @Override String label() { return "in"; }
        };

        private final double factor;

        Unit(double factor) {
            this.factor = factor;
        }

        abstract String label();
    }

    public @interface Marker {
        String value() default "";
    }

    static {
        System.loadLibrary("shapes");
    }
}
