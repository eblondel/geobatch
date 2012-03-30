/*
 *  GeoBatch - Open Source geospatial batch processing system
 *  http://geobatch.codehaus.org/
 *  Copyright (C) 2007-2012 GeoSolutions S.A.S.
 *  http://www.geo-solutions.it
 *
 *  GPLv3 + Classpath exception
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.geosolutions.geobatch.catalog;

/**
 * Handles info for description to the human interface.
 *
 * @author ETj
 */
public interface Descriptable extends Identifiable {

    /**
     * A short name for the object.
     */
    public abstract String getName();

    /**
     * A short name for the object.
     */
    public abstract void setName(String name);


    /**
     * A longer description of the object.
     */
    public abstract String getDescription();


    /**
     * A longer description of the object.
     */
    public abstract void setDescription(String description);

}