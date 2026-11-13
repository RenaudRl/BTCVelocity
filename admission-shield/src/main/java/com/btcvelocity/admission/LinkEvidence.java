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
import org.jspecify.annotations.Nullable;

/**
 * Why an account-link verdict was given, as the journal and {@code /admission why} state it.
 * Never an address: the linked account is named by its UUID, the address by nothing.
 *
 * @param strength the link that counted, or {@code null} for a count of accounts alone
 * @param linked the linked account, or {@code null} for a count of accounts alone
 * @param sanctionId the id of the active network ban of the linked account, or {@code null}
 * @param accountsOnAddress the distinct accounts seen on the address over the address history,
 *     the entering one included
 */
public record LinkEvidence(
    Link.@Nullable Strength strength,
    @Nullable UUID linked,
    @Nullable Long sanctionId,
    int accountsOnAddress) {
}
