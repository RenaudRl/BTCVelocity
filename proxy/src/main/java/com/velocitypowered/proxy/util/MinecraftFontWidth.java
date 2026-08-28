/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.util;

/**
 * Advance widths, in pixels, of the Minecraft default font.
 *
 * <p>The Minecraft font is proportional: {@code i} is 2 pixels wide while {@code m} is 6. Any
 * layout computed by counting characters is therefore wrong, and drifts further the more text is
 * involved. All values below are <em>advance</em> widths, i.e. glyph width plus the single pixel of
 * inter-character spacing the client inserts.
 *
 * <p>Bold text adds one pixel of advance to every character.
 */
public final class MinecraftFontWidth {

  /** Advance width used for any printable codepoint without a dedicated entry. */
  public static final int DEFAULT_WIDTH = 6;

  /** Advance width used for full-width (CJK and friends) codepoints. */
  public static final int WIDE_WIDTH = 9;

  /** Extra advance added to every character when bold is active. */
  public static final int BOLD_EXTRA = 1;

  /** Advance width of a space, the unit used for padding. */
  public static final int SPACE_WIDTH = 4;

  /** Advance widths for codepoints 32..126; anything else falls back to the heuristics above. */
  private static final int[] ASCII_WIDTHS = new int[127 - 32];

  static {
    java.util.Arrays.fill(ASCII_WIDTHS, DEFAULT_WIDTH);
    put(' ', 4);
    put('!', 2);
    put('"', 5);
    put('\'', 3);
    put('(', 5);
    put(')', 5);
    put('*', 5);
    put(',', 2);
    put('.', 2);
    put(':', 2);
    put(';', 2);
    put('<', 5);
    put('>', 5);
    put('@', 7);
    put('I', 4);
    put('[', 4);
    put(']', 4);
    put('`', 3);
    put('f', 5);
    put('i', 2);
    put('k', 5);
    put('l', 3);
    put('t', 4);
    put('{', 4);
    put('|', 2);
    put('}', 4);
    put('~', 7);
  }

  private MinecraftFontWidth() {
  }

  private static void put(final char c, final int width) {
    ASCII_WIDTHS[c - 32] = width;
  }

  /**
   * Returns the advance width, in pixels, of a single codepoint.
   *
   * @param codePoint the codepoint to measure
   * @param bold whether bold formatting is active
   * @return the advance width in pixels, or {@code 0} for codepoints that render nothing
   */
  public static int widthOf(final int codePoint, final boolean bold) {
    if (codePoint == '\n' || codePoint == '\r') {
      return 0;
    }
    // Private Use Area: reserved for our own internal markers, never rendered.
    if (codePoint >= 0xE000 && codePoint <= 0xF8FF) {
      return 0;
    }

    final int base;
    if (codePoint >= 32 && codePoint < 127) {
      base = ASCII_WIDTHS[codePoint - 32];
    } else if (isWide(codePoint)) {
      base = WIDE_WIDTH;
    } else {
      base = DEFAULT_WIDTH;
    }
    return bold ? base + BOLD_EXTRA : base;
  }

  /**
   * Returns the advance width, in pixels, of a plain string.
   *
   * @param text the text to measure
   * @param bold whether bold formatting is active
   * @return the advance width in pixels
   */
  public static int widthOf(final String text, final boolean bold) {
    int width = 0;
    for (int i = 0; i < text.length(); ) {
      final int codePoint = text.codePointAt(i);
      width += widthOf(codePoint, bold);
      i += Character.charCount(codePoint);
    }
    return width;
  }

  private static boolean isWide(final int codePoint) {
    return (codePoint >= 0x1100 && codePoint <= 0x115F)      // Hangul Jamo
        || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)      // CJK radicals .. Yi
        || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)      // Hangul syllables
        || (codePoint >= 0xF900 && codePoint <= 0xFAFF)      // CJK compatibility ideographs
        || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)      // CJK compatibility forms
        || (codePoint >= 0xFF00 && codePoint <= 0xFF60)      // Fullwidth forms
        || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6);     // Fullwidth signs
  }
}
