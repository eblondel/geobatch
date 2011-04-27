/*
 *  GeoBatch - Open Source geospatial batch processing system
 *  http://geobatch.codehaus.org/
 *  Copyright (C) 2007-2008-2009 GeoSolutions S.A.S.
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
package it.geosolutions.geobatch.geoserver.geotiff;

import it.geosolutions.filesystemmonitor.monitor.FileSystemEvent;
import it.geosolutions.geobatch.catalog.file.FileBaseCatalog;
import it.geosolutions.geobatch.flow.event.action.ActionException;
import it.geosolutions.geobatch.geoserver.GeoServerActionConfiguration;
import it.geosolutions.geobatch.geoserver.GeoServerAction;
import it.geosolutions.geobatch.geoserver.GeoServerRESTHelper;
import it.geosolutions.geobatch.global.CatalogHolder;
import it.geosolutions.geobatch.tools.file.Path;
import it.geosolutions.geoserver.rest.GeoServerRESTPublisher;
import it.geosolutions.geoserver.rest.decoder.RESTCoverageStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.apache.commons.io.FilenameUtils;
import org.geotools.gce.geotiff.GeoTiffFormat;
import org.geotools.gce.geotiff.GeoTiffReader;

/**
 * Comments here ...
 * 
 * @author AlFa
 * 
 * @version $ GeoTIFFOverviewsEmbedder.java $ Revision: x.x $ 23/mar/07 11:42:25
 */
public class GeoTIFFGeoServerGenerator extends GeoServerAction<FileSystemEvent> {

    public final static String GEOSERVER_VERSION = "1.7.X";

    protected GeoTIFFGeoServerGenerator(GeoServerActionConfiguration configuration)
            throws IOException {
        super(configuration);
    }

    public Queue<FileSystemEvent> execute(Queue<FileSystemEvent> events) throws ActionException {
        try {
            listenerForwarder.started();

            // looking for file
            if (events.size() != 1) {
                throw new IllegalArgumentException("Wrong number of elements for this action: "
                        + events.size());
            }
            FileSystemEvent event = events.remove();
            final String configId = configuration.getName();

            // //
            // data flow configuration and dataStore name must not be null.
            // //
            if (configuration == null) {
                LOGGER.error("DataFlowConfig is null.");
                throw new IllegalStateException("DataFlowConfig is null.");
            }
            // ////////////////////////////////////////////////////////////////////
            //
            // Initializing input variables
            //
            // ////////////////////////////////////////////////////////////////////
            final File workingDir = Path.findLocation(configuration.getWorkingDirectory(),
                    new File(((FileBaseCatalog) CatalogHolder.getCatalog()).getBaseDirectory()));

            // ////////////////////////////////////////////////////////////////////
            //
            // Checking input files.
            //
            // ////////////////////////////////////////////////////////////////////
            if ((workingDir == null) || !workingDir.exists() || !workingDir.isDirectory()) {
                LOGGER.error("GeoServerDataDirectory is null or does not exist.");
                throw new IllegalStateException("GeoServerDataDirectory is null or does not exist.");
            }

            // checked by superclass
            // if ((geoserverURL == null) || "".equals(geoserverURL)) {
            // LOGGER.error("GeoServerCatalogServiceURL is null.");
            // throw new
            // IllegalStateException("GeoServerCatalogServiceURL is null.");
            // }

            String inputFileName = event.getSource().getAbsolutePath();
            final String filePrefix = FilenameUtils.getBaseName(inputFileName);
            final String fileSuffix = FilenameUtils.getExtension(inputFileName);
            final String fileNameFilter = getConfiguration().getStoreFilePrefix();

            String baseFileName = null;

            if (fileNameFilter != null) {
                if ((filePrefix.equals(fileNameFilter) || filePrefix.matches(fileNameFilter))
                        && ("tif".equalsIgnoreCase(fileSuffix) || "tiff"
                                .equalsIgnoreCase(fileSuffix))) {
                    // etj: are we missing something here?
                    baseFileName = filePrefix;
                }
            } else if ("tif".equalsIgnoreCase(fileSuffix) || "tiff".equalsIgnoreCase(fileSuffix)) {
                baseFileName = filePrefix;
            }

            if (baseFileName == null) {
                LOGGER.error("Unexpected file '" + inputFileName + "'");
                throw new IllegalStateException("Unexpected file '" + inputFileName + "'");
            }

            inputFileName = FilenameUtils.getName(inputFileName);
            final String coverageStoreId = FilenameUtils.getBaseName(inputFileName);

            // //
            // creating coverageStore
            // //
            final GeoTiffFormat format = new GeoTiffFormat();
            GeoTiffReader coverageReader = null;

            // //
            // Trying to read the GeoTIFF
            // //
            /**
             * GeoServer url: "file:data/" + coverageStoreId + "/" + geoTIFFFileName
             */
            try {
                coverageReader = (GeoTiffReader) format.getReader(event.getSource());

                if (coverageReader == null) {
                    LOGGER.error("No valid GeoTIFF File found for this Data Flow!");
                    throw new IllegalStateException(
                            "No valid GeoTIFF File found for this Data Flow!");
                }
            } finally {
                if (coverageReader != null) {
                    try {
                        coverageReader.dispose();
                    } catch (Throwable e) {
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace(e.getLocalizedMessage(), e);
                        }
                    }
                }
            }

            // ////////////////////////////////////////////////////////////////////
            //
            // SENDING data to GeoServer via REST protocol.
            //
            // ////////////////////////////////////////////////////////////////////
            Map<String, String> queryParams = new HashMap<String, String>();
            queryParams.put("namespace", getConfiguration().getDefaultNamespace());
            queryParams.put("wmspath", getConfiguration().getWmsPath());
            send(workingDir, event.getSource(), getConfiguration().getGeoserverURL(), new Long(
                    event.getTimestamp()).toString(), coverageStoreId, baseFileName,
                    getConfiguration().getStyles(), configId, getConfiguration().getDefaultStyle(),
                    queryParams);

            listenerForwarder.completed();
            return events;
        } catch (Exception t) {
            LOGGER.error(t.getLocalizedMessage(), t); // no need to
            // log,
            // we're
            // rethrowing
            // it
            listenerForwarder.failed(t);
            throw new ActionException(this, t.getMessage(), t);
        }
    }

    /**
     * <B>TODO: REST calls here: restruct to use GS2 </B>
     */
    private void send(final File inputDataDir, final File data, final String geoserverBaseURL,
            final String timeStamp, final String coverageStoreId, final String storeFilePrefix,
            final List<String> dataStyles, final String configId, final String defaultStyle,
            final Map<String, String> queryParams) throws MalformedURLException,
            FileNotFoundException {
        URL geoserverREST_URL = null;
        boolean sent = false;

        String layerName = storeFilePrefix != null ? storeFilePrefix : coverageStoreId;

        GeoServerRESTPublisher publisher = new GeoServerRESTPublisher(
                getConfiguration().getGeoserverURL(), 
                getConfiguration().getGeoserverUID(), 
                getConfiguration().getGeoserverPWD());

        
        if ("DIRECT".equals(getConfiguration().getDataTransferMethod())) {
            sent = publisher.publishGeoTIFF(queryParams.get("namespace"), coverageStoreId, data);
        } else if ("EXTERNAL".equals(getConfiguration().getDataTransferMethod())) {
            RESTCoverageStore store = publisher.publishExternalGeoTIFF(queryParams.get("namespace"), layerName, data, configuration.getDefaultStyle());
            sent = store != null;
        } else {
            throw new IllegalStateException("Unknown transfer method " + getConfiguration().getDataTransferMethod());
        }
        
        if (sent) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("GeoTIFF GeoServerAction: coverage SUCCESSFULLY sent to GeoServer!");
            }
        } else {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("GeoTIFF GeoServerAction: coverage was NOT sent to GeoServer due to connection errors!");
            }
        }
    }
}
