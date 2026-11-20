import { readFile } from "fs/promises";

/** A 2D vector. */
export class Vec {
  constructor(public x: number, public y: number) {}

  /** Length of the vector. */
  get length(): number {
    return Math.hypot(this.x, this.y);
  }

  add(o: Vec): Vec {
    return new Vec(this.x + o.x, this.y + o.y);
  }

  static zero = () => new Vec(0, 0);

  private scaled = (k: number): Vec => {
    return new Vec(this.x * k, this.y * k);
  };

  toString() {
    return `Vec(${this.x}, ${this.y}) {`;
  }
}

export interface Shape {
  area(): number;
  name: string;
}

export abstract class Base implements Shape {
  abstract area(): number;
  name = "base";

  describe(): string {
    const parts = [this.name, String(this.area())];
    return parts.map((p) => `<${p}>`).join(" ");
  }
}

enum Color {
  Red = "red",
  Green = "green",
}

export namespace Geometry {
  export function dot(a: Vec, b: Vec): number {
    return a.x * b.x + a.y * b.y;
  }

  export const cross = (a: Vec, b: Vec) => a.x * b.y - a.y * b.x;
}

export async function load(path: string): Promise<Vec[]> {
  const text = await readFile(path, "utf8");
  return text
    .split("\n")
    .filter((l) => l.trim() !== "")
    .map((l) => {
      const [x, y] = l.split(",").map(Number);
      return new Vec(x, y);
    });
}

export const sum = (vs: Vec[]): Vec =>
  vs.reduce((acc, v) => acc.add(v), new Vec(0, 0));

const config = {
  retries: 3,
  backoff(attempt: number) {
    return 2 ** attempt * 100;
  },
};

function* range(n: number) {
  for (let i = 0; i < n; i++) yield i;
}

const re = /[{}]/g;

describe("Vec", () => {
  it("adds", () => {
    expect(new Vec(1, 2).add(new Vec(3, 4))).toEqual(new Vec(4, 6));
  });
});
