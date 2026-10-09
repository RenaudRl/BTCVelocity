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

import java.net.URI;

/**
 * One list: what class it puts an address in, where it is downloaded from, where it is kept.
 *
 * @param id the name shown in the journal as the source of a verdict
 * @param ipClass the class every address of this list belongs to
 * @param url where the public list is downloaded from
 * @param fileName the file it is kept in, inside the module's list directory
 */
public record ListSource(String id, IpClass ipClass, URI url, String fileName) {
}
