import { Assets, Texture } from "pixi.js";

/**
 * Loads a Texture from a URL, transparently handling base64 `data:` images.
 *
 * The hardened Content-Security-Policy `connect-src` directive blocks the
 * `data:` scheme, and PixiJS's `Assets.load()` *fetches* its URL — so a data-URL
 * sprite (mob/pet portrait, room art, ability icon shipped inline by the server)
 * would fail to load and fall back to a flat colored placeholder. The `img-src`
 * directive *does* permit `data:`, and an `<img>` element is governed by
 * `img-src`, so we decode data URLs through one instead. Ordinary http(s) and
 * app-relative URLs defer to `Assets.load()`, preserving its caching.
 */
export async function loadTexture(path: string): Promise<Texture> {
  if (path.startsWith("data:")) {
    const img = new Image();
    img.src = path;
    await img.decode();
    return Texture.from(img);
  }
  return Assets.load(path);
}
