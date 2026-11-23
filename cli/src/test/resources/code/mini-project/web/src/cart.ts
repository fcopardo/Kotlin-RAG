// The cart badge in the page header.
export interface CartItem {
  sku: string;
  quantity: number;
}

export function itemCount(items: CartItem[]): number {
  return items.reduce((sum, item) => sum + item.quantity, 0);
}

export class CartBadge {
  constructor(private readonly element: HTMLElement) {}

  render(items: CartItem[]): void {
    this.element.textContent = `${itemCount(items)}`;
  }
}
