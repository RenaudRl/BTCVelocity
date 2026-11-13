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

import java.util.UUID;

/**
 * One account the entering account is linked to, and how strongly.
 *
 * <p>A link is only ever computed between the entering account and the accounts seen on ITS
 * address or ITS network: A linked to B and B linked to C never link A to C.
 *
 * @param account the other account
 * @param strength how the two are linked
 */
public record Link(UUID account, Strength strength) {

  /** How two accounts are linked. */
  public enum Strength {
    /**
     * Same exact address within {@code strong-window-days}, and the address is not shared (at most
     * {@code shared-address-accounts} distinct accounts on it). The only link that may refuse.
     */
    STRONG("strong"),
    /** Same /24 or /64, the same address seen longer ago, or a shared address. Never refuses. */
    WEAK("weak"),
    /** Declared by the staff as one household: never refused, never signalled for it. */
    HOUSEHOLD("household");

    private final String label;

    Strength(final String label) {
      this.label = label;
    }

    /**
     * The words written in the journal.
     *
     * @return the label
     */
    public String label() {
      return this.label;
    }
  }
}
