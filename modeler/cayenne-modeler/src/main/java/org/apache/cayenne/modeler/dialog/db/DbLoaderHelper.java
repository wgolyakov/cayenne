/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *    or more contributor license agreements.  See the NOTICE file
 *    distributed with this work for additional information
 *    regarding copyright ownership.  The ASF licenses this file
 *    to you under the Apache License, Version 2.0 (the
 *    "License"); you may not use this file except in compliance
 *    with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing,
 *    software distributed under the License is distributed on an
 *    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *    KIND, either express or implied.  See the License for the
 *    specific language governing permissions and limitations
 *    under the License.
 */

package org.apache.cayenne.modeler.dialog.db;

import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.access.DbLoader;
import org.apache.cayenne.access.loader.DbLoaderConfiguration;
import org.apache.cayenne.access.loader.DefaultDbLoaderDelegate;
import org.apache.cayenne.access.loader.filters.OldFilterConfigBridge;
import org.apache.cayenne.configuration.DataChannelDescriptor;
import org.apache.cayenne.configuration.DefaultConfigurationNameMapper;
import org.apache.cayenne.configuration.event.DataMapEvent;
import org.apache.cayenne.dba.DbAdapter;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.map.event.EntityEvent;
import org.apache.cayenne.map.event.MapEvent;
import org.apache.cayenne.map.naming.DefaultUniqueNameGenerator;
import org.apache.cayenne.map.naming.NameCheckers;
import org.apache.cayenne.modeler.Application;
import org.apache.cayenne.modeler.ProjectController;
import org.apache.cayenne.modeler.event.DataMapDisplayEvent;
import org.apache.cayenne.modeler.util.LongRunningTask;
import org.apache.cayenne.resource.Resource;
import org.apache.cayenne.tools.dbimport.config.FiltersConfigBuilder;
import org.apache.cayenne.tools.dbimport.config.ReverseEngineering;
import org.apache.cayenne.util.DeleteRuleUpdater;
import org.apache.cayenne.util.Util;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Stateful helper class that encapsulates access to DbLoader.
 * 
 */
public class DbLoaderHelper {

    private static Log logObj = LogFactory.getLog(DbLoaderHelper.class);

    // TODO: this is a temp hack... need to delegate to DbAdapter, or
    // configurable in
    // preferences...
    private static final Collection<String> EXCLUDED_TABLES = Arrays.asList("AUTO_PK_SUPPORT", "auto_pk_support");

    protected boolean stoppingReverseEngineering;
    protected boolean existingMap;

    protected ProjectController mediator;
    protected String dbUserName;
    protected String dbCatalog;
    protected DbLoader loader;
    protected DataMap dataMap;
    protected boolean meaningfulPk;
    protected List<String> schemas;
    protected List<String> catalogs;
    protected DbAdapter adapter;

    private final OldFilterConfigBridge filterBuilder = new OldFilterConfigBridge();

    protected String loadStatusNote;

    /**
     * ObjEntities which were added to project during reverse engineering
     */
    protected List<ObjEntity> addedObjEntities;

    public DbLoaderHelper(ProjectController mediator, Connection connection, DbAdapter adapter, String dbUserName) {
        this.dbUserName = dbUserName;
        this.mediator = mediator;
        try {
            this.dbCatalog = connection.getCatalog();
        } catch (SQLException e) {
            logObj.warn("Error getting catalog.", e);
        }
        this.adapter = adapter;
        this.loader = new DbLoader(connection, adapter, new LoaderDelegate());
    }

    public void setStoppingReverseEngineering(boolean stopReverseEngineering) {
        this.stoppingReverseEngineering = stopReverseEngineering;
    }

    public boolean isStoppingReverseEngineering() {
        return stoppingReverseEngineering;
    }

    /**
     * Performs reverse engineering of the DB using internal DbLoader. This
     * method should be invoked outside EventDispatchThread, or it will throw an
     * exception.
     */
    public void execute() {
        stoppingReverseEngineering = false;

        // load catalogs...
        if (adapter.supportsCatalogsOnReverseEngineering()) {
            LongRunningTask loadCatalogsTask = new LoadCatalogsTask(Application.getFrame(), "Loading Catalogs");
            loadCatalogsTask.startAndWait();
        }

        if (stoppingReverseEngineering) {
            return;
        }

        // load schemas...
        LongRunningTask loadSchemasTask = new LoadSchemasTask(Application.getFrame(), "Loading Schemas");
        loadSchemasTask.startAndWait();

        if (stoppingReverseEngineering) {
            return;
        }

        final DbLoaderOptionsDialog dialog = new DbLoaderOptionsDialog(schemas, catalogs, dbUserName, dbCatalog, false);

        try {
            // since we are not inside EventDispatcher Thread, must run it via
            // SwingUtilities
            SwingUtilities.invokeAndWait(new Runnable() {

                public void run() {
                    dialog.setVisible(true);
                    dialog.dispose();
                }
            });
        } catch (Throwable th) {
            processException(th, "Error Reengineering Database");
            return;
        }

        if (dialog.getChoice() == DbLoaderOptionsDialog.CANCEL) {
            return;
        }

        this.filterBuilder.catalog(dialog.getSelectedCatalog());
        this.filterBuilder.schema(dialog.getSelectedSchema());
        this.filterBuilder.includeTables(dialog.getTableNamePattern());
        this.filterBuilder.setProceduresFilters(dialog.isLoadingProcedures());
        this.filterBuilder.includeProcedures(dialog.getProcedureNamePattern());

        this.meaningfulPk = dialog.isMeaningfulPk();
        this.addedObjEntities = new ArrayList<ObjEntity>();

        this.loader.setNameGenerator(dialog.getNamingStrategy());

        // load DataMap...
        LongRunningTask loadDataMapTask = new LoadDataMapTask(Application.getFrame(), "Reengineering DB");
        loadDataMapTask.startAndWait();
    }

    protected void processException(final Throwable th, final String message) {
        logObj.info("Exception on reverse engineering", Util.unwindException(th));
        cleanup();
        SwingUtilities.invokeLater(new Runnable() {

            public void run() {
                JOptionPane.showMessageDialog(Application.getFrame(), th.getMessage(), message,
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    protected void cleanup() {
        loadStatusNote = "Closing connection...";
        try {
            if (loader.getConnection() != null) {
                loader.getConnection().close();
            }
        } catch (SQLException e) {
            logObj.warn("Error closing connection.", e);
        }
    }

    private final class LoaderDelegate extends DefaultDbLoaderDelegate {

        @Override
        public void dbEntityAdded(DbEntity entity) {
            checkCanceled();

            loadStatusNote = "Importing table '" + entity.getName() + "'...";

            // TODO: hack to prevent PK tables from being visible... this should
            // really be
            // delegated to DbAdapter to decide...
            if (EXCLUDED_TABLES.contains(entity.getName()) && entity.getDataMap() != null) {
                entity.getDataMap().removeDbEntity(entity.getName());
            } else if (existingMap) {
                mediator.fireDbEntityEvent(new EntityEvent(this, entity, MapEvent.ADD));
            }
        }

        @Override
        public void objEntityAdded(ObjEntity entity) {
            checkCanceled();

            loadStatusNote = "Creating ObjEntity '" + entity.getName() + "'...";
            addedObjEntities.add(entity);

            if (existingMap) {
                mediator.fireObjEntityEvent(new EntityEvent(this, entity, MapEvent.ADD));
            }
        }

        @Override
        public void dbEntityRemoved(DbEntity entity) {
            checkCanceled();

            if (existingMap) {
                mediator.fireDbEntityEvent(new EntityEvent(Application.getFrame(), entity, MapEvent.REMOVE));
            }
        }

        @Override
        public void objEntityRemoved(ObjEntity entity) {
            checkCanceled();

            if (existingMap) {
                mediator.fireObjEntityEvent(new EntityEvent(Application.getFrame(), entity, MapEvent.REMOVE));
            }
        }

        @Override
        public boolean dbRelationship(DbEntity entity) {
            checkCanceled();

            loadStatusNote = "Load relationships for '" + entity.getName() + "'...";

            return true;
        }

        @Override
        public boolean dbRelationshipLoaded(DbEntity entity, DbRelationship relationship) {
            checkCanceled();

            loadStatusNote = "Load relationship: '" + entity.getName() + "'; '" + relationship.getName() + "'...";

            return true;
        }

        void checkCanceled() {
            if (isStoppingReverseEngineering()) {
                throw new CayenneRuntimeException("Reengineering was canceled.");
            }
        }
    }

    abstract class DbLoaderTask extends LongRunningTask {

        public DbLoaderTask(JFrame frame, String title) {
            super(frame, title);
            setMinValue(0);
            setMaxValue(10);
        }

        @Override
        protected String getCurrentNote() {
            return loadStatusNote;
        }

        @Override
        protected int getCurrentValue() {
            return getMinValue();
        }

        @Override
        protected boolean isIndeterminate() {
            return true;
        }

        @Override
        public boolean isCanceled() {
            return isStoppingReverseEngineering();
        }

        @Override
        public void setCanceled(boolean b) {
            if (b) {
                loadStatusNote = "Canceling..";
            }

            setStoppingReverseEngineering(b);
        }
    }

    final class LoadSchemasTask extends DbLoaderTask {

        public LoadSchemasTask(JFrame frame, String title) {
            super(frame, title);
        }

        @Override
        protected void execute() {
            loadStatusNote = "Loading available schemas...";

            try {
                schemas = loader.getSchemas();
            } catch (Throwable th) {
                processException(th, "Error Loading Schemas");
            }
        }
    }

    final class LoadCatalogsTask extends DbLoaderTask {

        public LoadCatalogsTask(JFrame frame, String title) {
            super(frame, title);
        }

        @Override
        protected void execute() {
            loadStatusNote = "Loading available catalogs...";

            try {
                catalogs = loader.getCatalogs();
            } catch (Throwable th) {
                processException(th, "Error Loading Catalogs");
            }
        }
    }

    final class LoadDataMapTask extends DbLoaderTask {

        public LoadDataMapTask(JFrame frame, String title) {
            super(frame, title);
        }

        @Override
        protected void execute() {

            loadStatusNote = "Preparing...";

            DbLoaderHelper.this.dataMap = mediator.getCurrentDataMap();
            DbLoaderHelper.this.existingMap = dataMap != null;

            if (!existingMap) {
                dataMap = new DataMap(DefaultUniqueNameGenerator.generate(NameCheckers.dataMap));
                dataMap.setName(DefaultUniqueNameGenerator.generate(NameCheckers.dataMap, mediator.getProject().getRootNode()));
                dataMap.setDefaultCatalog(filterBuilder.catalog());
                dataMap.setDefaultSchema(filterBuilder.schema());
            }

            if (isCanceled()) {
                return;
            }

            importingTables();
            importingProcedures();

            cleanup();

            // fire up events
            loadStatusNote = "Updating view...";
            if (mediator.getCurrentDataMap() != null) {
                mediator.fireDataMapEvent(new DataMapEvent(Application.getFrame(), dataMap, MapEvent.CHANGE));
                mediator.fireDataMapDisplayEvent(new DataMapDisplayEvent(Application.getFrame(), dataMap,
                        (DataChannelDescriptor) mediator.getProject().getRootNode(), mediator.getCurrentDataNode()));
            } else {
                DataChannelDescriptor currentDomain = (DataChannelDescriptor) mediator.getProject().getRootNode();
                Resource baseResource = currentDomain.getConfigurationSource();

                // this will be new data map so need to set configuration source
                // for it
                if (baseResource != null) {
                    DefaultConfigurationNameMapper nameMapper = new DefaultConfigurationNameMapper();
                    Resource dataMapResource = baseResource.getRelativeResource(nameMapper.configurationLocation(dataMap));
                    dataMap.setConfigurationSource(dataMapResource);
                }
                mediator.addDataMap(Application.getFrame(), dataMap);
            }
        }

        private void importingProcedures() {
            if (!filterBuilder.isLoadProcedures()) {
                return;
            }

            loadStatusNote = "Importing procedures...";
            try {
                DbLoaderConfiguration configuration = new DbLoaderConfiguration();
                configuration.setFiltersConfig(new FiltersConfigBuilder(new ReverseEngineering())
                        .add(filterBuilder).filtersConfig());

                loader.loadProcedures(dataMap, new DbLoaderConfiguration());
            } catch (Throwable th) {
                if (!isCanceled()) {
                    processException(th, "Error Reengineering Database");
                }
            }
        }

        private void importingTables() {
            loadStatusNote = "Importing tables...";
            try {
                loader.setCreatingMeaningfulPK(meaningfulPk);
               
                DbLoaderConfiguration configuration = new DbLoaderConfiguration();
                configuration.setFiltersConfig(new FiltersConfigBuilder(new ReverseEngineering())
                        .add(filterBuilder).filtersConfig());
                loader.load(dataMap, configuration);

                /**
                 * Update default rules for relationships
                 */
                for (ObjEntity addedObjEntity : addedObjEntities) {
                    DeleteRuleUpdater.updateObjEntity(addedObjEntity);
                }
            } catch (Throwable th) {
                if (!isCanceled()) {
                    processException(th, "Error Reengineering Database");
                }
            }
        }
    }
}
