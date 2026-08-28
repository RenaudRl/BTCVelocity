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

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Renders the server list MOTD, laying out its segments using real Minecraft font metrics.
 *
 * <p>A MOTD line is written in MiniMessage and may contain any number of the alignment tags
 * {@code <left>}, {@code <center>} and {@code <right>}. Each tag opens a new segment that runs until
 * the next alignment tag or the end of the line, so a single line can mix alignments:
 *
 * <pre>{@code
 * motd-line1 = "<center><gold>BornToCraft<right><gray>1.26"
 * }</pre>
 *
 * <p>Text before the first alignment tag is left-aligned, which makes a MOTD without any tag render
 * exactly as it is written.
 *
 * <p>Segments are positioned by measuring their pixel width with {@link MinecraftFontWidth} — bold
 * text and non-ASCII glyphs included — and padding with spaces. Because a space is
 * {@value MinecraftFontWidth#SPACE_WIDTH} pixels wide, positions are accurate to within half a
 * space; the client font offers no finer unit.
 */
public final class MotdRenderer {

  /** Default usable width, in pixels, of the MOTD area in the vanilla server list. */
  public static final int DEFAULT_MOTD_WIDTH = 270;

  // Private Use Area codepoints: internal segment markers, never rendered by the client.
  private static final char LEFT_MARKER = (char) 0xE000;
  private static final char CENTER_MARKER = (char) 0xE001;
  private static final char RIGHT_MARKER = (char) 0xE002;

  private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
      .editTags(tags -> tags
          .resolver(TagResolver.resolver("left",
              Tag.selfClosingInserting(Component.text(LEFT_MARKER))))
          .resolver(TagResolver.resolver("center",
              Tag.selfClosingInserting(Component.text(CENTER_MARKER))))
          .resolver(TagResolver.resolver("right",
              Tag.selfClosingInserting(Component.text(RIGHT_MARKER)))))
      .build();

  private MotdRenderer() {
  }

  /**
   * Renders both MOTD lines into a single component.
   *
   * @param line1 the first line, in MiniMessage format
   * @param line2 the second line, in MiniMessage format; may be empty
   * @param motdWidth the usable width of the MOTD area, in pixels
   * @return the laid out MOTD
   */
  public static Component render(final String line1, final String line2, final int motdWidth) {
    final Component first = renderLine(line1, motdWidth);
    if (line2 == null || line2.isEmpty()) {
      return first;
    }
    return first.append(Component.newline()).append(renderLine(line2, motdWidth));
  }

  /**
   * Renders a single MOTD line, resolving its alignment tags into space padding.
   *
   * @param rawText the line, in MiniMessage format
   * @param motdWidth the usable width of the MOTD area, in pixels
   * @return the laid out line
   */
  public static Component renderLine(final String rawText, final int motdWidth) {
    if (rawText == null || rawText.isEmpty()) {
      return Component.empty();
    }

    final List<Segment> segments = splitIntoSegments(flatten(MINI_MESSAGE.deserialize(rawText)));
    if (segments.isEmpty()) {
      return Component.empty();
    }

    final List<Component> out = new ArrayList<>();
    int cursor = 0;
    boolean first = true;

    for (final Segment segment : segments) {
      final int desiredStart = switch (segment.alignment) {
        case CENTER -> (motdWidth - segment.width) / 2;
        case RIGHT -> motdWidth - segment.width;
        case LEFT -> first ? 0 : cursor;
      };
      // Never overlap the previous segment: fall back to a single separating space.
      final int minimumStart = first ? 0 : cursor + MinecraftFontWidth.SPACE_WIDTH;
      final int start = Math.max(desiredStart, minimumStart);

      final int spaces = roundToSpaces(start - cursor);
      if (spaces > 0) {
        out.add(Component.text(" ".repeat(spaces)));
      }
      for (final Run run : segment.runs) {
        out.add(Component.text(run.text).style(run.style));
      }

      cursor = cursor + spaces * MinecraftFontWidth.SPACE_WIDTH + segment.width;
      first = false;
    }

    return Component.text().append(out).build();
  }

  /**
   * Renders a MOTD line as plain text, without any alignment padding.
   *
   * <p>Used by protocols such as GameSpy Query where the padding spaces would be noise rather than
   * layout.
   *
   * @param rawText the line, in MiniMessage format
   * @return the plain text of the line
   */
  public static String renderPlain(final String rawText) {
    if (rawText == null || rawText.isEmpty()) {
      return "";
    }
    final String plain = PlainTextComponentSerializer.plainText()
        .serialize(MINI_MESSAGE.deserialize(rawText));
    return stripMarkers(plain);
  }

  /**
   * Returns the pixel width of a MOTD line, ignoring its alignment tags.
   *
   * @param rawText the line, in MiniMessage format
   * @return the width in pixels
   */
  public static int widthOf(final String rawText) {
    int width = 0;
    for (final Run run : flatten(MINI_MESSAGE.deserialize(rawText))) {
      width += MinecraftFontWidth.widthOf(stripMarkers(run.text), run.bold);
    }
    return width;
  }

  private static int roundToSpaces(final int pixels) {
    if (pixels <= 0) {
      return 0;
    }
    return (pixels + MinecraftFontWidth.SPACE_WIDTH / 2) / MinecraftFontWidth.SPACE_WIDTH;
  }

  private static String stripMarkers(final String text) {
    if (text.indexOf(LEFT_MARKER) < 0
        && text.indexOf(CENTER_MARKER) < 0
        && text.indexOf(RIGHT_MARKER) < 0) {
      return text;
    }
    final StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c != LEFT_MARKER && c != CENTER_MARKER && c != RIGHT_MARKER) {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Flattens a component tree into a list of styled text runs, resolving inherited styles.
   *
   * <p>Flattening lets the whole line be parsed by MiniMessage in one pass — so gradients and
   * colours span alignment tags without breaking — and only then be cut into segments.
   */
  private static List<Run> flatten(final Component component) {
    final List<Run> runs = new ArrayList<>();
    flatten(component, Style.empty(), runs);
    return runs;
  }

  private static void flatten(final Component component, final Style inherited,
      final List<Run> runs) {
    final Style style = inherited.merge(component.style(), Style.Merge.Strategy.ALWAYS);

    final String content;
    if (component instanceof TextComponent text) {
      content = text.content();
    } else {
      content = PlainTextComponentSerializer.plainText()
          .serialize(component.children(List.of()));
    }
    if (!content.isEmpty()) {
      runs.add(new Run(content, style, style.hasDecoration(TextDecoration.BOLD)));
    }

    for (final Component child : component.children()) {
      flatten(child, style, runs);
    }
  }

  /** Cuts the flattened runs into segments delimited by the alignment markers. */
  private static List<Segment> splitIntoSegments(final List<Run> runs) {
    final List<Segment> segments = new ArrayList<>();
    Segment current = new Segment(Alignment.LEFT);

    for (final Run run : runs) {
      int start = 0;
      for (int i = 0; i < run.text.length(); i++) {
        final Alignment alignment = markerAlignment(run.text.charAt(i));
        if (alignment == null) {
          continue;
        }
        current.add(run.text.substring(start, i), run.style, run.bold);
        if (!current.isEmpty()) {
          segments.add(current);
        }
        current = new Segment(alignment);
        start = i + 1;
      }
      current.add(run.text.substring(start), run.style, run.bold);
    }

    if (!current.isEmpty()) {
      segments.add(current);
    }
    return segments;
  }

  private static Alignment markerAlignment(final char c) {
    return switch (c) {
      case LEFT_MARKER -> Alignment.LEFT;
      case CENTER_MARKER -> Alignment.CENTER;
      case RIGHT_MARKER -> Alignment.RIGHT;
      default -> null;
    };
  }

  private enum Alignment {
    LEFT, CENTER, RIGHT
  }

  private record Run(String text, Style style, boolean bold) {
  }

  private static final class Segment {

    private final Alignment alignment;
    private final List<Run> runs = new ArrayList<>();
    private int width;

    private Segment(final Alignment alignment) {
      this.alignment = alignment;
    }

    private void add(final String text, final Style style, final boolean bold) {
      if (text.isEmpty()) {
        return;
      }
      runs.add(new Run(text, style, bold));
      width += MinecraftFontWidth.widthOf(text, bold);
    }

    private boolean isEmpty() {
      return runs.isEmpty();
    }
  }
}
