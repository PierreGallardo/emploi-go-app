import type { MapThemeId } from './map-themes';

// Images drawn for the map: the badges of the transit stops (pictograms of lucide, ISC licence) and the pin of the chosen place.
const pictograms = {
  tram: '<rect width="16" height="16" x="4" y="3" rx="2"/><path d="M4 11h16"/><path d="M12 3v8"/><path d="m8 19-2 3"/><path d="m18 22-2-3"/><path d="M8 15h.01"/><path d="M16 15h.01"/>',
  bus: '<path d="M8 6v6"/><path d="M15 6v6"/><path d="M2 12h19.6"/><path d="M18 18h3s.5-1.7.8-2.8c.1-.4.2-.8.2-1.2 0-.4-.1-.8-.2-1.2l-1.4-5C20.1 6.8 19.1 6 18 6H4a2 2 0 0 0-2 2v10h3"/><circle cx="7" cy="18" r="2"/><path d="M9 18h5"/><circle cx="16" cy="18" r="2"/>',
} as const;
export type StopMode = keyof typeof pictograms;

const badges: Record<MapThemeId, string> = {
  light: '#3f4a59',
  dark: '#6b7686',
};
/** Rendered at twice this size on the map (`pixelRatio: 2`). */
const size = 44;

export const stopIconId = (mode: StopMode, theme: MapThemeId) =>
  `stop-${mode}-${theme}`;
const idPattern = /^stop-(tram|bus)-(light|dark)$/;

export function stopIconSvg(mode: StopMode, theme: MapThemeId) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}"><rect x="2" y="2" width="${size - 4}" height="${size - 4}" rx="10" fill="${badges[theme]}" stroke="#ffffff" stroke-width="2"/><g transform="translate(10 10)" fill="none" stroke="#ffffff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${pictograms[mode]}</g></svg>`;
}

const pins: Record<MapThemeId, string> = {
  light: '#e0352b',
  dark: '#ff6b5e',
};
export const placePinId = (theme: MapThemeId) => `place-pin-${theme}`;
const pinPattern = /^place-pin-(light|dark)$/;
const pinSize = { width: 36, height: 48 };

/** A location pin, its tip at the bottom centre of the image. */
export function placePinSvg(theme: MapThemeId) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${pinSize.width}" height="${pinSize.height}" viewBox="0 0 36 48"><path d="M18 46C18 46 4 30.5 4 18a14 14 0 0 1 28 0C32 30.5 18 46 18 46Z" fill="${pins[theme]}" stroke="#ffffff" stroke-width="2.5" stroke-linejoin="round"/><circle cx="18" cy="18" r="5.5" fill="#ffffff"/></svg>`;
}

async function decode(svg: string, width: number, height: number) {
  const image = new Image(width, height);
  image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  await image.decode();
  return image;
}

/** The image of a stop icon or of the pin that the style asks for, or null for any other image. */
export async function loadMapIcon(id: string) {
  const stop = idPattern.exec(id);
  if (stop)
    return decode(
      stopIconSvg(stop[1] as StopMode, stop[2] as MapThemeId),
      size,
      size,
    );
  const pin = pinPattern.exec(id);
  if (pin)
    return decode(
      placePinSvg(pin[1] as MapThemeId),
      pinSize.width,
      pinSize.height,
    );
  return null;
}
