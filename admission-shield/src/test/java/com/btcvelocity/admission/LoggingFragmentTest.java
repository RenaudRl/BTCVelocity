/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.btcvelocity.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class LoggingFragmentTest {

  private static Document fragment() throws Exception {
    try (InputStream xml = LoggingFragmentTest.class.getResourceAsStream(
        "/log4j2-admission-journal.xml")) {
      assertNotNull(xml, "the fragment must ship in the jar");
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      return factory.newDocumentBuilder().parse(xml);
    }
  }

  private static Element only(final Document document, final String tag) {
    assertEquals(1, document.getElementsByTagName(tag).getLength(), tag);
    return (Element) document.getElementsByTagName(tag).item(0);
  }

  @Test
  void theJournalLoggerHasItsOwnFileAndStaysOutOfTheMainLog() throws Exception {
    final Document document = fragment();

    final Element logger = only(document, "Logger");
    assertEquals(AdmissionJournal.LOGGER_NAME, logger.getAttribute("name"));
    assertEquals("false", logger.getAttribute("additivity"));
    final Element appender = only(document, "RollingRandomAccessFile");
    assertEquals(appender.getAttribute("name"),
        ((Element) logger.getElementsByTagName("AppenderRef").item(0)).getAttribute("ref"));
  }

  @Test
  void theFileRotatesDailyAndIsDeletedAfter180Days() throws Exception {
    final Document document = fragment();

    final Element appender = only(document, "RollingRandomAccessFile");
    assertTrue(appender.getAttribute("filePattern").contains("%d{yyyy-MM-dd}"),
        "one file per day");
    only(document, "TimeBasedTriggeringPolicy");
    final Element delete = only(document, "Delete");
    assertTrue(appender.getAttribute("filePattern").startsWith(delete.getAttribute("basePath")),
        "the purge looks where the archives are written");
    assertEquals("P180D", only(document, "IfLastModified").getAttribute("age"));
    assertTrue(appender.getAttribute("filePattern").endsWith(".log.gz"));
    assertEquals("journal-*.log.gz", only(document, "IfFileName").getAttribute("glob"));
  }
}
