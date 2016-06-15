/*
 * Copyright (c) 2015, Excelsior LLC.
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
package com.excelsiorjet.api.util;

import com.excelsiorjet.api.log.AbstractLog;

/**
 * @author Nikita Lipsky
 */
public class Txt {

    private static Messages messages = new Messages("Strings");
    public static AbstractLog log;

    public static String s(String id, Object... params) {
        String str = messages.format(id, params);
        if (str != null) {
            return str;
        } else {
            if (log != null) {
                log.error("JET message file broken: key = " + id);
            } else {
                throw new IllegalStateException("No log to issue error. JET message file broken: key = " + id);
            }
            return id;
        }
    }
}
