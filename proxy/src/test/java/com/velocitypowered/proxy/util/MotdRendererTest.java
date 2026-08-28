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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class MotdRendererTest {

  private static final int WIDTH = MotdRenderer.DEFAULT_MOTD_WIDTH;

  private static String plain(final String raw) {
    return PlainTextComponentSerializer.plainText().serialize(MotdRenderer.renderLine(raw, WIDTH));
  }

  private static int leadingSpaces(final String text) {
    int i = 0;
    while (i < text.length() && text.charAt(i) == ' ') {
      i++;
    }
    return i;
  }

  @Test
  void measuresAsciiWithProportionalWidths() {
    // i=2, l=3, m=6 — a character count would report 3 for all of them.
    assertEquals(2, MinecraftFontWidth.widthOf("i", false));
    assertEquals(3, MinecraftFontWidth.widthOf("l", false));
    assertEquals(6, MinecraftFontWidth.widthOf("m", false));
    assertEquals(4, MinecraftFontWidth.widthOf(" ", false));
  }

  @Test
  void boldAddsOnePixelPerCharacter() {
    assertEquals(MinecraftFontWidth.widthOf("abc", false) + 3,
        MinecraftFontWidth.widthOf("abc", true));
  }

  @Test
  void alignmentTagsDoNotCountTowardsWidth() {
    assertEquals(MotdRenderer.widthOf("abc"), MotdRenderer.widthOf("<center>abc"));
  }

  @Test
  void centersIndependentlyOfTextLength() {
    // The original char-counting implementation drifted left as the text grew: a longer line
    // received *fewer* padding spaces. Here both lines must stay centred on the same axis.
    for (final String text : new String[]{"Hi", "A Much Longer Server Name Here"}) {
      final String rendered = plain("<center>" + text);
      final int padding = leadingSpaces(rendered) * MinecraftFontWidth.SPACE_WIDTH;
      final int centreOfText = padding + MinecraftFontWidth.widthOf(text, false) / 2;
      // Accurate to within half a space, the finest unit the font offers.
      assertTrue(Math.abs(centreOfText - WIDTH / 2) <= MinecraftFontWidth.SPACE_WIDTH,
          "centre off by " + (centreOfText - WIDTH / 2) + "px for \"" + text + "\"");
    }
  }

  @Test
  void centersBoldTextCorrectly() {
    final String text = "BornToCraft";
    final String rendered = plain("<b><center>" + text);
    final int padding = leadingSpaces(rendered) * MinecraftFontWidth.SPACE_WIDTH;
    final int centreOfText = padding + MinecraftFontWidth.widthOf(text, true) / 2;
    assertTrue(Math.abs(centreOfText - WIDTH / 2) <= MinecraftFontWidth.SPACE_WIDTH,
        "bold centre off by " + (centreOfText - WIDTH / 2) + "px");
  }

  @Test
  void rightAlignsToTheEndOfTheLine() {
    final String text = "1.26";
    final String rendered = plain("<right>" + text);
    final int end = leadingSpaces(rendered) * MinecraftFontWidth.SPACE_WIDTH
        + MinecraftFontWidth.widthOf(text, false);
    assertTrue(Math.abs(end - WIDTH) <= MinecraftFontWidth.SPACE_WIDTH,
        "right edge off by " + (end - WIDTH) + "px");
  }

  @Test
  void laysOutSeveralSegmentsOnOneLine() {
    final String rendered = plain("<center>Title<right>1.26");
    assertTrue(rendered.contains("Title"));
    assertTrue(rendered.endsWith("1.26"));

    final int titleStart = rendered.indexOf("Title");
    final int titlePadding = titleStart * MinecraftFontWidth.SPACE_WIDTH;
    final int titleCentre = titlePadding + MinecraftFontWidth.widthOf("Title", false) / 2;
    assertTrue(Math.abs(titleCentre - WIDTH / 2) <= MinecraftFontWidth.SPACE_WIDTH,
        "centre segment moved by " + (titleCentre - WIDTH / 2) + "px");
  }

  @Test
  void leavesUntaggedLinesUntouched() {
    assertEquals("Plain server name", plain("<gold>Plain server name"));
  }

  @Test
  void neverEmitsNegativePaddingForOversizedSegments() {
    final String text = "x".repeat(200);
    final String rendered = plain("<center>" + text);
    assertEquals(0, leadingSpaces(rendered));
    assertEquals(text, rendered);
  }

  @Test
  void keepsSegmentsFromOverlapping() {
    // Two wide segments cannot both sit where they want; they must still stay separated.
    final String rendered = plain("<center>" + "a".repeat(40) + "<right>" + "b".repeat(20));
    assertTrue(rendered.contains("a b") || rendered.contains("a  b"),
        "segments were not separated: " + rendered);
  }

  @Test
  void measuresUnicodeOutsideTheAsciiTable() {
    // Symbols used in the live MOTD: not in the table, so they take the default advance.
    assertEquals(MinecraftFontWidth.DEFAULT_WIDTH, MinecraftFontWidth.widthOf("♜", false));
    assertEquals(MinecraftFontWidth.WIDE_WIDTH, MinecraftFontWidth.widthOf("日", false));
  }

  @Test
  void renderPlainStripsTagsAndPadding() {
    assertEquals("Title1.26", MotdRenderer.renderPlain("<center><gold>Title<right>1.26"));
  }

  @Test
  void rendersBothLinesSeparatedByNewline() {
    final String rendered = PlainTextComponentSerializer.plainText()
        .serialize(MotdRenderer.render("<center>One", "<center>Two", WIDTH));
    assertEquals(2, rendered.lines().count());
  }

  @Test
  void omitsTheSecondLineWhenEmpty() {
    final String rendered = PlainTextComponentSerializer.plainText()
        .serialize(MotdRenderer.render("One", "", WIDTH));
    assertEquals("One", rendered);
  }
}
