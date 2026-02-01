/*
 * Copyright (C) 2025 David Rolfe
 *
 * Use of this source code is governed by an MIT
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */
/*
 * Copyright (C) 2025 Volt Active Data Inc.
 *
 * Use of this source code is governed by an MIT
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */

package ie.rolfe.redischarglt.documents;

import com.google.gson.Gson;

/**
 * Class that gets stored as JSON data.
 *
 */
public class ExtraUserData extends AbstractBaseTable {

    public static final String NEW_LOYALTY_NUMBER = "NEW_LOYALTY_NUMBER";

    public String mysteriousHexPayload;

    public String loyaltySchemeName;

    public long loyaltySchemeNumber;

    public ExtraUserData(String mysteriousHexPayload, String loyaltySchemeName, long loyaltySchemeNumber) {
        this.mysteriousHexPayload = mysteriousHexPayload;
        this.loyaltySchemeName = loyaltySchemeName;
        this.loyaltySchemeNumber = loyaltySchemeNumber;
    }

    public ExtraUserData() {
    }

    public static ExtraUserData fromJson(Gson gson, String document) {
        return new Gson().fromJson(document, ExtraUserData.class);
    }

    @Override
    public String toString() {
        return "ExtraUserData{" +
                "mysteriousHexPayload='" + mysteriousHexPayload + '\'' +
                ", loyaltySchemeName='" + loyaltySchemeName + '\'' +
                ", loyaltySchemeNumber=" + loyaltySchemeNumber +
                '}';
    }
}
