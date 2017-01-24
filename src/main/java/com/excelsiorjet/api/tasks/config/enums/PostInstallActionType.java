/*
 * Copyright (c) 2017, Excelsior LLC.
 *
 *  This file is part of Excelsior JET API.
 *
 *  Excelsior JET API is free software:
 *  you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Excelsior JET API is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Excelsior JET API.
 *  If not, see <http://www.gnu.org/licenses/>.
 *
*/
package com.excelsiorjet.api.tasks.config.enums;

import com.excelsiorjet.api.util.Utils;

/**
 * (Windows) Excelsior Installer post install actions enumeration.
 */
public enum PostInstallActionType {
    RUN,
    OPEN,
    RESTART;

    public String toString() {
        return Utils.enumConstantNameToParameter(name());
    }

    public static PostInstallActionType fromString(String postInstallAction) {
        if (postInstallAction == null) {
            return null;
        }
        try {
            return PostInstallActionType.valueOf(Utils.parameterToEnumConstantName(postInstallAction));
        } catch (Exception e) {
            return null;
        }
    }
}
