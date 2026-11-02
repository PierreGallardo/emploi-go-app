// WCAG contrast of a colour written as `#rrggbb` or `hsl(h s% l%)`, as the map themes and the sectors write them.

function channels(color: string): [number, number, number] {
  if (color.startsWith('#'))
    return [1, 3, 5].map((start) =>
      parseInt(color.slice(start, start + 2), 16),
    ) as [number, number, number];
  const [h, s, l] = (color.match(/[\d.]+/g) ?? []).map(Number) as [
    number,
    number,
    number,
  ];
  const saturation = s / 100;
  const lightness = l / 100;
  const a = saturation * Math.min(lightness, 1 - lightness);
  const channel = (n: number) => {
    const k = (n + h / 30) % 12;
    return 255 * (lightness - a * Math.max(-1, Math.min(k - 3, 9 - k, 1)));
  };
  return [channel(0), channel(8), channel(4)];
}

const linear = (channel: number) => {
  const value = channel / 255;
  return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
};

/** Relative luminance, from 0 (black) to 1 (white). */
export function luminance(color: string) {
  const [r, g, b] = channels(color).map(linear) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** Contrast ratio between two colours, from 1 to 21. */
export function contrastRatio(a: string, b: string) {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [
    number,
    number,
  ];
  return (light + 0.05) / (dark + 0.05);
}
