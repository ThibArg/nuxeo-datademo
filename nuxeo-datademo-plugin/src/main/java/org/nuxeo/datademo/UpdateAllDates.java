/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Thibaud Arguillere
 */
package org.nuxeo.datademo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.datademo.tools.ToolsMisc;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.IterableQueryResult;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.EventListenerDescriptor;
import org.nuxeo.ecm.core.event.impl.EventListenerList;
import org.nuxeo.ecm.core.event.impl.EventServiceImpl;
import org.nuxeo.ecm.core.schema.DocumentType;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.core.schema.types.Field;
import org.nuxeo.ecm.core.schema.types.Schema;
import org.nuxeo.ecm.core.schema.types.Type;
import org.nuxeo.ecm.platform.dublincore.listener.DublinCoreListener;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * 
 *
 * @since 7.1
 */
public class UpdateAllDates {

    private static final Log log = LogFactory.getLog(UpdateAllDates.class);

    CoreSession session;

    int diffInDays;

    ArrayList<String> enabledListeners = new ArrayList<String>();

    boolean wasBlockAsyncHandlers;

    boolean wasBlockSyncPostCommitHandlers;

    public UpdateAllDates(CoreSession inSession, int inDays) {

        session = inSession;
        diffInDays = inDays;
    }

    public UpdateAllDates(CoreSession inSession, Date inLastUpdate) {

        session = inSession;

        long diffInMs = Calendar.getInstance().getTimeInMillis()
                - inLastUpdate.getTime();
        diffInDays = (int) TimeUnit.DAYS.convert(diffInMs,
                TimeUnit.MILLISECONDS);
        if (diffInMs < 86400000 || diffInDays < 1) {
            diffInDays = 0;
        }
    }

    public void run(boolean inDisableListeners) {

        if (diffInDays < 1) {
            log.error("Date received is in the future or less than one day: No update done");
            return;
        }

        ToolsMisc.forceLogInfo(log,
                "\n--------------------\nIncrease all dates by " + diffInDays
                        + " days\n--------------------");

        if(inDisableListeners) {
            disableListeners();
        }

        SchemaManager sm = Framework.getLocalService(SchemaManager.class);
        DocumentType[] allTypes = sm.getDocumentTypes();
        for (DocumentType dt : allTypes) {
            Collection<Schema> schemas = dt.getSchemas();
            ArrayList<String> xpaths = new ArrayList<String>();

            for (Schema schema : schemas) {
                for (Field field : schema.getFields()) {
                    Type t = field.getType();
                    
                    // PAsser par: field.getType().getTypeHierarchy()
                    // et récupérer en fait le dernier type => ce sera le type de base
                    if (t.isSimpleType() && t.getName().equals("date")) {
                        xpaths.add("" + field.getName());
                    }
                }
            }

            if (xpaths.size() > 0) {
                String nxql;
                DocumentModelList allDocs;

                ToolsMisc.forceLogInfo(log,
                        "Update dates for documents of type: " + dt.getName());
                nxql = "SELECT * FROM " + dt.getName();
                allDocs = session.query(nxql);
                updateDocs(allDocs, xpaths);
                
             // à voir => utiliser l'iterator de queryAndFetch
             //   IterableQueryResult iqr = session.queryAndFetch(query, queryType, params);
             //   iqr.iterator().next();
                
                // Ou un PageProvider
            }

        }

        if(inDisableListeners) {
            restoreListeners();
        }
    }

    protected void disableListeners() {

        ToolsMisc.forceLogInfo(log, "Disabling all listeners...");

        EventServiceImpl esi = (EventServiceImpl) Framework.getService(EventService.class);

        wasBlockAsyncHandlers = esi.isBlockAsyncHandlers();
        wasBlockSyncPostCommitHandlers = esi.isBlockSyncPostCommitHandlers();
        esi.setBlockAsyncHandlers(true);
        esi.setBlockSyncPostCommitHandlers(true);

        EventListenerList ell = esi.getListenerList();

        ArrayList<EventListenerDescriptor> descs = new ArrayList<EventListenerDescriptor>();
        descs.addAll(ell.getEnabledInlineListenersDescriptors());
        //descs.addAll(ell.getEnabledAsyncPostCommitListenersDescriptors());
        //descs.addAll(ell.getEnabledSyncPostCommitListenersDescriptors());

        for (EventListenerDescriptor d : descs) {
            enabledListeners.add(d.getName());
            d.setEnabled(false);
        }

        ell.recomputeEnabledListeners();

        ToolsMisc.forceLogInfo(log,
                "Disabled listeners: " + enabledListeners.toString());
    }

    protected void restoreListeners() {

        ToolsMisc.forceLogInfo(log, "Restoring the listeners...");

        EventServiceImpl esi = (EventServiceImpl) Framework.getService(EventService.class);
        esi.setBlockAsyncHandlers(wasBlockAsyncHandlers);
        esi.setBlockSyncPostCommitHandlers(wasBlockSyncPostCommitHandlers);

        EventListenerList ell = esi.getListenerList();

        ArrayList<EventListenerDescriptor> descs = new ArrayList<EventListenerDescriptor>();
        descs.addAll(ell.getInlineListenersDescriptors());
        //descs.addAll(ell.getAsyncPostCommitListenersDescriptors());
        //descs.addAll(ell.getSyncPostCommitListenersDescriptors());

        for (EventListenerDescriptor d : descs) {
            if (enabledListeners.contains(d.getName())) {
                d.setEnabled(true);
            }
        }

        ell.recomputeEnabledListeners();
    }
    
    /**
     * Update all date fields whose xpaths are passed in <code>inXPaths</code>.
     * <p>
     * No control/check if the document has the correct schema.
     * 
     * @param inDocs
     * @param inXPaths
     *
     * @since 7.2
     */
    protected void updateDocs(DocumentModelList inDocs, ArrayList<String> inXPaths) {
        
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();
        
        int count = 0;
        for(DocumentModel oneDoc : inDocs) {
            
            for(String xpath : inXPaths) {
                updateDate(oneDoc, xpath);
            }
            /*
             * UTILISER session.saveDocuments() (pluriel)
             */
            
            // Save without dublincore and custom events (in the Studio project)
            //oneDoc.putContextData(DublinCoreListener.DISABLE_DUBLINCORE_LISTENER, true);
            //oneDoc.putContextData("UpdatingData_NoEventPlease", true);
            oneDoc = session.saveDocument(oneDoc);
            
            count += 1;
            if((count % 50) == 0) {
                TransactionHelper.commitOrRollbackTransaction();
                TransactionHelper.startTransaction();
            }
            if((count % 500) == 0) {
                ToolsMisc.forceLogInfo(log, "" + count);
            }
        }

        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();
        
    }

    protected void updateDate(DocumentModel inDoc, String inXPath) {

        Calendar d = (Calendar) inDoc.getPropertyValue(inXPath);
        if (d != null) {
            d.add(Calendar.DATE, diffInDays);
            inDoc.setPropertyValue(inXPath, d);
        }
    }

}