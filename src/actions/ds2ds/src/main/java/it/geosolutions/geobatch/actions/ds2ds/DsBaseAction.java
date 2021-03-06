/*
 *  Copyright (C) 2013 GeoSolutions S.A.S.
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
package it.geosolutions.geobatch.actions.ds2ds;

import it.geosolutions.filesystemmonitor.monitor.FileSystemEvent;
import it.geosolutions.filesystemmonitor.monitor.FileSystemEventType;
import it.geosolutions.geobatch.actions.ds2ds.dao.FeatureConfiguration;
import it.geosolutions.geobatch.configuration.event.action.ActionConfiguration;
import it.geosolutions.geobatch.flow.event.action.ActionException;
import it.geosolutions.geobatch.flow.event.action.BaseAction;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.EventObject;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.DataUtilities;
import org.geotools.data.FeatureStore;
import org.geotools.data.Query;
import org.geotools.data.Transaction;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author ETj (etj at geo-solutions.it)
 */
public abstract class DsBaseAction extends BaseAction<EventObject> {

	private final static Logger LOGGER = LoggerFactory.getLogger(DsBaseAction.class);

	protected Ds2dsConfiguration configuration = null;

	public DsBaseAction(ActionConfiguration actionConfiguration) {
		super(actionConfiguration);
		configuration = (Ds2dsConfiguration)actionConfiguration.clone();
	}
    
    /**
     * Purge data on output feature, if requested.
     *
     * @param featureWriter
     * @throws IOException
     */
    protected void purgeData(FeatureStore<SimpleFeatureType, SimpleFeature> featureWriter) throws IOException {
        if (configuration.isPurgeData()) {
            updateTask("Purging existing data");
            featureWriter.removeFeatures(Filter.INCLUDE);
            updateTask("Data purged");
        }
    }

    protected void updateTask(String task) {
        listenerForwarder.setTask(task);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(task);
        }
    }

    /**
     * Builds a FeatureStore for the output Feature.
     *
     * @param store
     * @param schema
     * @return
     * @throws IOException
     */
    protected FeatureStore<SimpleFeatureType, SimpleFeature> createOutputWriter(DataStore store, SimpleFeatureType schema, Transaction transaction) throws IOException {
        String destTypeName = schema.getTypeName();
        boolean createSchema = true;
        for (String typeName : store.getTypeNames()) {
            if (typeName.equalsIgnoreCase(destTypeName)) {
                createSchema = false;
                destTypeName = typeName;
            }
        }
        // check for case changing in typeName
        if (createSchema) {
            store.createSchema(schema);
            for (String typeName : store.getTypeNames()) {
                if (!typeName.equals(destTypeName) && typeName.equalsIgnoreCase(destTypeName)) {
                    destTypeName = typeName;
                }
            }
        }
        FeatureStore<SimpleFeatureType, SimpleFeature> result = (FeatureStore<SimpleFeatureType, SimpleFeature>) store.getFeatureSource(destTypeName);
        result.setTransaction(transaction);
        return result;
    }

    /**
     * Builds a Feature instance to be written on output.
     *
     * @param builder
     * @param sourceFeature
     * @return
     */
    protected SimpleFeature buildFeature(SimpleFeatureBuilder builder, SimpleFeature sourceFeature, Map<String, String> mappings) {
        for (AttributeDescriptor ad : builder.getFeatureType().getAttributeDescriptors()) {
            String attribute = ad.getLocalName();
            builder.set(attribute, getAttributeValue(sourceFeature, attribute, mappings));
        }
        return builder.buildFeature(null);
    }

    /**
     * Compare input and output schemas for different case mapping in attribute names.
     *
     * @param destSchema
     * @param schema
     * @return
     */
    protected Map<String, String> compareSchemas(SimpleFeatureType destSchema, SimpleFeatureType schema) {
        Map<String, String> diffs = new HashMap<String, String>();
        for (AttributeDescriptor ad : destSchema.getAttributeDescriptors()) {
            String attribute = ad.getLocalName();
            if (schema.getDescriptor(attribute) == null) {
                for (String variant : getNameVariants(attribute)) {
                    if (schema.getDescriptor(variant) != null) {
                        diffs.put(attribute, variant);
                        break;
                    }
                }
            }
        }
        return diffs;
    }

    /**
     * Creates the destination DataStore, from the configuration connection parameters.
     *
     * @return
     * @throws IOException
     * @throws ActionException
     */
    protected DataStore createOutputDataStore() throws IOException, ActionException {
        updateTask("Connecting to output DataStore");
        return createDataStore(configuration.getOutputFeature().getDataStore());
    }

    /**
     * Updates the import progress ( progress / total )
     * for the listeners.
     *
     * @param progress
     * @param total
     * @param message
     */
    protected void updateImportProgress(int progress, int total, String message) {
        listenerForwarder.progressing((float) progress, message);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Importing data: " + progress + "/" + total);
        }
    }

    /**
     * Builds the output event, with information about the imported data.
     *
     * @param outputEvents
     * @param schema
     * @return
     * @throws FileNotFoundException
     * @throws ActionException
     */
    protected EventObject buildOutputEvent() throws FileNotFoundException, ActionException {
        updateTask("Building output event");
        FileOutputStream outStream = null;
        try {
            File outputDir = getTempDir();
            File outputFile = new File(outputDir.getAbsolutePath()
+ File.separator + "output.xml");

//                    new File(outputDir, "output.xml");
            outStream = new FileOutputStream(outputFile);
            configuration.getOutputFeature().toXML(outStream);
            updateTask("Output event built");
            return new FileSystemEvent(outputFile, FileSystemEventType.FILE_ADDED);
        } catch (Exception e) {
            throw new ActionException(this, "Error writing output event");
        } finally {
            IOUtils.closeQuietly(outStream);
        }
    }

    protected void closeResource(DataStore dataStore) {
        if (dataStore != null) {
            try {
                dataStore.dispose();
            } catch (Throwable t) {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error("Error closing datastore connection");
                }
            }
        }
    }

    protected void closeResource(Transaction transaction) {
        if (transaction != null) {
            try {
                transaction.close();
            } catch (Throwable t) {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error("Error closing transaction");
                }
            }
        }
    }

    protected void failAction(String message) throws ActionException {
        failAction(message, null);
    }

    protected void failAction(String message, Throwable t) throws ActionException {
        if (LOGGER.isErrorEnabled()) {
            LOGGER.error(message);
            if (t != null) {
                LOGGER.error(getStackTrace(t));
            }
        }
        if (!configuration.isFailIgnored()) {
            final ActionException e = new ActionException(this, message, t);
            listenerForwarder.failed(e);
            throw e;
        }
    }

    /**
     * Builds an attribute value to be written on output.
     * @param sourceFeature source used to get values to write
     * @param attributeName name of the attribute in the output feature
     * @return
     */
    protected Object getAttributeValue(SimpleFeature sourceFeature, String attributeName, Map<String, String> mappings) {
        // gets mapping for renamed attributes
        if (configuration.getAttributeMappings().containsKey(attributeName)) {
            attributeName = configuration.getAttributeMappings().get(attributeName).toString();
        } else if (mappings.containsKey(attributeName)) {
            attributeName = mappings.get(attributeName);
        }
        return sourceFeature.getAttribute(attributeName);
    }

    /**
     * Returns case variants for the given name.
     *
     * @param name
     * @return
     */
    protected String[] getNameVariants(String name) {
        return new String[]{name.toLowerCase(), name.toUpperCase()};
    }

	private String getStackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

    /**
     * Creates a DataStore from the given connection parameters.
     *
     * @param connect
     * @return
     * @throws IOException
     * @throws ActionException
     */
    protected DataStore createDataStore(Map<String, Serializable> connect) throws IOException, ActionException {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("DataStore connection parameters:");
            for (String connectKey : connect.keySet()) {
                LOGGER.info(connectKey + " -> " + connect.get(connectKey));
            }
        }
        DataStore dataStore = DataStoreFinder.getDataStore(connect);
        if (dataStore == null) {
            failAction("Cannot connect to DataStore: wrong parameters");
        }
        return dataStore;
    }

	protected DataStore createSourceDataStore(FileSystemEvent fileEvent) throws IOException, ActionException {
		updateTask("Connecting to source DataStore");
		String fileType = getFileType(fileEvent);
		FeatureConfiguration sourceFeature = configuration.getSourceFeature();
		if(fileType.equals("xml")) {
			InputStream inputXML = null;
			try {
				inputXML = new FileInputStream(fileEvent.getSource());
				sourceFeature  = FeatureConfiguration.fromXML(inputXML);
			} catch (Exception e) {
	            throw new IOException("Unable to load input XML", e);
	        } finally {
	            IOUtils.closeQuietly(inputXML);
	        }
		} else if(fileType.equals("shp")) {
			sourceFeature.getDataStore()
					.put("url", DataUtilities.fileToURL(fileEvent.getSource()));
		}
		DataStore source = createDataStore(sourceFeature.getDataStore());
		// if no typeName is configured, takes the first one registered in store
		if(sourceFeature.getTypeName() == null) {
			sourceFeature.setTypeName(source.getTypeNames()[0]);
		}
		// if no CRS is configured, takes if from the feature
		if (sourceFeature.getCrs() == null) {
			sourceFeature.setCoordinateReferenceSystem(source.getSchema(
					sourceFeature.getTypeName())
					.getCoordinateReferenceSystem());
		}
		configuration.setSourceFeature(sourceFeature);
		return source;
	}

	/**
	 * Builds a Query Object for the source Feature.
	 *
	 * @param sourceStore
	 * @return
	 * @throws IOException
	 */
	protected Query buildSourceQuery(DataStore sourceStore) throws IOException {
		Query query = new Query();
		query.setTypeName(configuration.getSourceFeature().getTypeName());
		query.setCoordinateSystem(configuration.getSourceFeature().getCoordinateReferenceSystem());
		return query;
	}

    /**
	 * Creates the source datastore reader.
	 *
	 * @param sourceDataStore
	 * @param transaction
	 * @param query
	 * @return
	 * @throws IOException
	 */
	protected FeatureStore<SimpleFeatureType, SimpleFeature> createSourceReader(
			DataStore sourceDataStore, final Transaction transaction,
			Query query) throws IOException {
		FeatureStore<SimpleFeatureType, SimpleFeature> featureReader =
				(FeatureStore<SimpleFeatureType, SimpleFeature>) sourceDataStore
				.getFeatureSource(query.getTypeName());
		featureReader.setTransaction(transaction);
		return featureReader;
	}
    
	public static String getFileType(FileSystemEvent event) {
		return FilenameUtils.getExtension(event.getSource().getName()).toLowerCase();
	}
}
