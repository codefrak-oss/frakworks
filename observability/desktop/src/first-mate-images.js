// first-mate-images.js — images pasted into First mate's compose input
// (issue #368). One set of limits and one content builder, shared by the
// strip (which refuses a paste before attaching it) and the manager in main
// (which re-checks every send, since the renderer is not trusted to have).
// Plain data only: an image is `{ media_type, data }`, `data` the base64 of
// the decoded bytes. No node: imports, so the renderer bundle can take it.
"use strict";

// The Claude API's image types, and its per-image size cap (decoded bytes).
const IMAGE_TYPES = Object.freeze(["image/png", "image/jpeg", "image/gif", "image/webp"]);
const IMAGE_MAX_BYTES = 5 * 1024 * 1024;
// Per message: keeps one turn (and its IPC payload) bounded.
const IMAGE_MAX_COUNT = 5;

const BASE64_RE = /^[A-Za-z0-9+/]*={0,2}$/;

// The first bytes each accepted type starts with (#371), so a file whose
// declared type is a lie is refused too. Checked on the first decoded bytes,
// via atob, which Node and the renderer both have.
const MAGIC = {
  "image/png": (b) => b.startsWith("\x89PNG\r\n\x1a\n"),
  "image/jpeg": (b) => b.startsWith("\xff\xd8\xff"),
  "image/gif": (b) => /^GIF8[79]a/.test(b),
  "image/webp": (b) => b.startsWith("RIFF") && b.slice(8, 12) === "WEBP",
};

/** Decoded byte length of a base64 string, without decoding it. */
const decodedBytes = (b64) => {
  const s = String(b64 || "");
  const pad = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0;
  return Math.floor((s.length * 3) / 4) - pad;
};

const fmtMB = (bytes) => `${(bytes / (1024 * 1024)).toFixed(1)} MB`;

/**
 * One image's refusal, or null when it may be sent. `index` is only for the
 * message.
 */
function imageError(image, index = 0) {
  const n = `image ${index + 1}`;
  if (!image || typeof image !== "object") return `${n} is not an image`;
  if (!IMAGE_TYPES.includes(image.media_type)) {
    return `${n} is ${image.media_type ? String(image.media_type) : "of no type"}; only PNG, JPEG, GIF and WebP are accepted`;
  }
  if (typeof image.data !== "string" || !image.data || image.data.length % 4 !== 0 || !BASE64_RE.test(image.data)) {
    return `${n} is not base64 data`;
  }
  const bytes = decodedBytes(image.data);
  if (bytes > IMAGE_MAX_BYTES) return `${n} is ${fmtMB(bytes)}; the limit is ${fmtMB(IMAGE_MAX_BYTES)}`;
  let head = "";
  try { head = atob(image.data.slice(0, 24)); } catch { head = ""; }
  if (!MAGIC[image.media_type](head)) {
    return `${n} is not a ${image.media_type.slice(6).toUpperCase()} file; only PNG, JPEG, GIF and WebP are accepted`;
  }
  return null;
}

/**
 * Checks a send's images: `{ ok: true, images }` with each one reduced to
 * `{ media_type, data }`, or `{ ok: false, error }`. Missing means none.
 */
function validateImages(images) {
  if (images == null) return { ok: true, images: [] };
  if (!Array.isArray(images)) return { ok: false, error: "images must be a list" };
  if (images.length > IMAGE_MAX_COUNT) {
    return { ok: false, error: `${images.length} images; at most ${IMAGE_MAX_COUNT} go with one message` };
  }
  for (let i = 0; i < images.length; i += 1) {
    const error = imageError(images[i], i);
    if (error) return { ok: false, error };
  }
  return { ok: true, images: images.map((im) => ({ media_type: im.media_type, data: im.data })) };
}

/**
 * The stream-json user message `content`: the plain text when there are no
 * images (exactly as before #368), else one image block per image, then the
 * text block (omitted when the text is empty).
 */
function buildContent(text, images) {
  const list = images || [];
  if (!list.length) return text;
  const blocks = list.map((im) => ({
    type: "image", source: { type: "base64", media_type: im.media_type, data: im.data },
  }));
  if (text) blocks.push({ type: "text", text });
  return blocks;
}

/**
 * The strip's paste check, before anything is read: which pasted files
 * (`{ type, size }`, size in bytes) may join the `attached` already there,
 * and a notice naming each refusal, or null when none was refused.
 */
function admitPaste(attached, files) {
  const accept = [];
  const refused = [];
  let count = attached;
  for (const f of files || []) {
    if (!f || !IMAGE_TYPES.includes(f.type)) {
      refused.push(`${(f && f.type) || "that file"} is not accepted (PNG, JPEG, GIF or WebP only)`);
    } else if (f.size > IMAGE_MAX_BYTES) {
      refused.push(`an image of ${fmtMB(f.size)} is over the ${fmtMB(IMAGE_MAX_BYTES)} limit`);
    } else if (count >= IMAGE_MAX_COUNT) {
      refused.push(`at most ${IMAGE_MAX_COUNT} images go with one message`);
    } else {
      accept.push(f);
      count += 1;
    }
  }
  const unique = [...new Set(refused)];
  return { accept, notice: unique.length ? `Not attached: ${unique.join("; ")}.` : null };
}

/** "1 image", "3 images". */
const imagesLabel = (n) => `${n} image${n === 1 ? "" : "s"}`;

module.exports = {
  IMAGE_TYPES, IMAGE_MAX_BYTES, IMAGE_MAX_COUNT,
  decodedBytes, imageError, validateImages, buildContent, admitPaste, imagesLabel,
};
