/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2008-2010 Sun Microsystems, Inc.
 * Portions Copyright 2011-2016 ForgeRock AS.
 * Portions copyright 2011 profiq s.r.o.
 */
package org.opends.server.plugins;

import static org.opends.messages.PluginMessages.*;
import static org.opends.server.protocols.internal.InternalClientConnection.*;
import static org.opends.server.protocols.internal.Requests.*;
import static org.opends.server.schema.SchemaConstants.*;
import static org.opends.server.util.StaticUtils.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.LocalizedIllegalArgumentException;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.config.server.ConfigChangeResult;
import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.config.server.ConfigurationChangeListener;
import org.forgerock.opendj.ldap.AttributeDescription;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.ModificationType;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.SearchScope;
import org.forgerock.opendj.ldap.schema.AttributeType;
import org.forgerock.opendj.server.config.meta.PluginCfgDefn;
import org.forgerock.opendj.server.config.meta.ReferentialIntegrityPluginCfgDefn.CheckReferencesScopeCriteria;
import org.forgerock.opendj.server.config.server.PluginCfg;
import org.forgerock.opendj.server.config.server.ReferentialIntegrityPluginCfg;
import org.opends.server.api.Backend;
import org.opends.server.api.DirectoryThread;
import org.opends.server.api.ServerShutdownListener;
import org.opends.server.api.plugin.DirectoryServerPlugin;
import org.opends.server.api.plugin.PluginResult;
import org.opends.server.api.plugin.PluginType;
import org.opends.server.core.DeleteOperation;
import org.opends.server.core.DirectoryServer;
import org.opends.server.core.ModifyOperation;
import org.opends.server.protocols.internal.InternalClientConnection;
import org.opends.server.protocols.internal.InternalSearchOperation;
import org.opends.server.protocols.internal.SearchRequest;
import org.opends.server.types.Attribute;
import org.opends.server.types.Attributes;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.Entry;
import org.opends.server.types.IndexType;
import org.opends.server.types.Modification;
import org.opends.server.types.SearchFilter;
import org.opends.server.types.SearchResultEntry;
import org.opends.server.types.operation.PostOperationDeleteOperation;
import org.opends.server.types.operation.PostOperationModifyDNOperation;
import org.opends.server.types.operation.PreOperationAddOperation;
import org.opends.server.types.operation.PreOperationModifyOperation;
import org.opends.server.types.operation.SubordinateModifyDNOperation;

/**
 * This class implements a Directory Server post operation plugin that performs
 * Referential Integrity processing on successful delete and modify DN
 * operations. The plugin uses a set of configuration criteria to determine
 * what attribute types to check referential integrity on, and, the set of
 * base DNs to search for entries that might need referential integrity
 * processing. If none of these base DNs are specified in the configuration,
 * then the public naming contexts are used as the base DNs by default.
 * <BR><BR>
 * The plugin also has an option to process changes in background using
 * a thread that wakes up periodically looking for change records in a log
 * file.
 */
public class ReferentialIntegrityPlugin
        extends DirectoryServerPlugin<ReferentialIntegrityPluginCfg>
        implements ConfigurationChangeListener<ReferentialIntegrityPluginCfg>,
                   ServerShutdownListener
{
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();



  /** Current plugin configuration. */
  private ReferentialIntegrityPluginCfg currentConfiguration;

  /** List of attribute types that will be checked during referential integrity processing. */
  private LinkedHashSet<AttributeType> attributeTypes = new LinkedHashSet<>();
  /** List of base DNs that limit the scope of the referential integrity checking. */
  private Set<DN> baseDNs = new LinkedHashSet<>();

  /**
   * The update interval the background thread uses. If it is 0, then
   * the changes are processed in foreground.
   */
  private long interval;

  /** The flag used by the background thread to check if it should exit. */
  private boolean stopRequested;

  /** The thread name. */
  private static final String name =
      "Referential Integrity Background Update Thread";

  /**
   * The name of the logfile that the update thread uses to process change
   * records. Defaults to "logs/referint", but can be changed in the
   * configuration.
   */
  private String logFileName;

  /** The File class that logfile corresponds to. */
  private File logFile;

  /** The Thread class that the background thread corresponds to. */
  private Thread backGroundThread;

  /**
   * Used to save a map in the modifyDN operation attachment map that holds
   * the old entry DNs and the new entry DNs related to a modify DN rename to
   * new superior operation.
   */
  public static final String MODIFYDN_DNS="modifyDNs";

  /**
   * Used to save a set in the delete operation attachment map that
   * holds the subordinate entry DNs related to a delete operation.
   */
  public static final String DELETE_DNS="deleteDNs";

  /**
   * Specifies the mapping between the attribute type (specified in the
   * attributeTypes list) and the filter which the plugin should use
   * to verify the integrity of the value of the given attribute.
   */
  private LinkedHashMap<AttributeType, SearchFilter> attrFiltMap = new LinkedHashMap<>();

  @Override
  public final void initializePlugin(Set<PluginType> pluginTypes,
                                     ReferentialIntegrityPluginCfg pluginCfg)
         throws ConfigException
  {
    pluginCfg.addReferentialIntegrityChangeListener(this);
    LinkedList<LocalizableMessage> unacceptableReasons = new LinkedList<>();

    if (!isConfigurationAcceptable(pluginCfg, unacceptableReasons))
    {
      throw new ConfigException(unacceptableReasons.getFirst());
    }

    applyConfigurationChange(pluginCfg);

    // Set up log file. Note: it is not allowed to change once the plugin is active.
    setUpLogFile(pluginCfg.getLogFile());
    interval=pluginCfg.getUpdateInterval();

    //Set up background processing if interval > 0.
    if(interval > 0)
    {
      setUpBackGroundProcessing();
    }
  }



  @Override
  public ConfigChangeResult applyConfigurationChange(
          ReferentialIntegrityPluginCfg newConfiguration)
  {
    final ConfigChangeResult ccr = new ConfigChangeResult();

    //Load base DNs from new configuration.
    LinkedHashSet<DN> newConfiguredBaseDNs = new LinkedHashSet<>(newConfiguration.getBaseDN());
    //Load attribute types from new configuration.
    LinkedHashSet<AttributeType> newAttributeTypes =
            new LinkedHashSet<>(newConfiguration.getAttributeType());

    // Load the attribute-filter mapping
    LinkedHashMap<AttributeType, SearchFilter> newAttrFiltMap = new LinkedHashMap<>();

    for (String attrFilt : newConfiguration.getCheckReferencesFilterCriteria())
    {
      int sepInd = attrFilt.lastIndexOf(":");
      String attr = attrFilt.substring(0, sepInd);
      String filtStr = attrFilt.substring(sepInd + 1);

      AttributeType attrType = DirectoryServer.getSchema().getAttributeType(attr);
      try
      {
        newAttrFiltMap.put(attrType, SearchFilter.createFilterFromString(filtStr));
      }
      catch (DirectoryException unexpected)
      {
        // This should never happen because the filter has already been verified.
        logger.error(unexpected.getMessageObject());
      }
    }

    //User is not allowed to change the logfile name, append a message that the
    //server needs restarting for change to take effect.
    // The first time the plugin is initialised the 'logFileName' is
    // not initialised, so in order to verify if it is equal to the new
    // log file name, we have to make sure the variable is not null.
    String newLogFileName=newConfiguration.getLogFile();
    if(logFileName != null && !logFileName.equals(newLogFileName))
    {
      ccr.setAdminActionRequired(true);
      ccr.addMessage(INFO_PLUGIN_REFERENT_LOGFILE_CHANGE_REQUIRES_RESTART.get(logFileName, newLogFileName));
    }

    //Switch to the new lists.
    baseDNs = newConfiguredBaseDNs;
    attributeTypes = newAttributeTypes;
    attrFiltMap = newAttrFiltMap;

    //If the plugin is enabled and the interval has changed, process that
    //change. The change might start or stop the background processing thread.
    long newInterval=newConfiguration.getUpdateInterval();
    if (newConfiguration.isEnabled() && newInterval != interval)
    {
      processIntervalChange(newInterval, ccr.getMessages());
    }

    currentConfiguration = newConfiguration;
    return ccr;
  }

  @Override
  public boolean isConfigurationAcceptable(PluginCfg configuration,
                                           List<LocalizableMessage> unacceptableReasons)
  {
    boolean isAcceptable = true;
    ReferentialIntegrityPluginCfg pluginCfg =
         (ReferentialIntegrityPluginCfg) configuration;

    for (PluginCfgDefn.PluginType t : pluginCfg.getPluginType())
    {
      switch (t)
      {
        case POSTOPERATIONDELETE:
        case POSTOPERATIONMODIFYDN:
        case SUBORDINATEMODIFYDN:
        case SUBORDINATEDELETE:
        case PREOPERATIONMODIFY:
        case PREOPERATIONADD:
          // These are acceptable.
          break;

        default:
          isAcceptable = false;
          unacceptableReasons.add(ERR_PLUGIN_REFERENT_INVALID_PLUGIN_TYPE.get(t));
      }
    }

    Set<DN> cfgBaseDNs = pluginCfg.getBaseDN();
    if (cfgBaseDNs == null || cfgBaseDNs.isEmpty())
    {
      cfgBaseDNs = DirectoryServer.getPublicNamingContexts().keySet();
    }

    // Iterate through all of the defined attribute types and ensure that they
    // have acceptable syntaxes and that they are indexed for equality below all
    // base DNs.
    Set<AttributeType> theAttributeTypes = pluginCfg.getAttributeType();
    for (AttributeType type : theAttributeTypes)
    {
      if (! isAttributeSyntaxValid(type))
      {
        isAcceptable = false;
        unacceptableReasons.add(
                       ERR_PLUGIN_REFERENT_INVALID_ATTRIBUTE_SYNTAX.get(
                            type.getNameOrOID(),
                             type.getSyntax().getName()));
      }

      for (DN baseDN : cfgBaseDNs)
      {
        Backend<?> b = DirectoryServer.getBackend(baseDN);
        if (b != null && !b.isIndexed(type, IndexType.EQUALITY))
        {
          isAcceptable = false;
          unacceptableReasons.add(ERR_PLUGIN_REFERENT_ATTR_UNINDEXED.get(
              pluginCfg.dn(), type.getNameOrOID(), b.getBackendID()));
        }
      }
    }

    /* Iterate through the attribute-filter mapping and verify that the
     * map contains attributes listed in the attribute-type parameter
     * and that the filter is valid.
     */

    for (String attrFilt : pluginCfg.getCheckReferencesFilterCriteria())
    {
      int sepInd = attrFilt.lastIndexOf(":");
      String attr = attrFilt.substring(0, sepInd).trim();
      String filtStr = attrFilt.substring(sepInd + 1).trim();

      /* TODO: strip the ;options part? */

      /* Get the attribute type for the given attribute. The attribute
       * type has to be present in the attributeType list.
       */

      AttributeType attrType = DirectoryServer.getSchema().getAttributeType(attr);
      if (attrType.isPlaceHolder() || !theAttributeTypes.contains(attrType))
      {
        isAcceptable = false;
        unacceptableReasons.add(ERR_PLUGIN_REFERENT_ATTR_NOT_LISTED.get(attr));
      }

      /* Verify the filter. */
      try
      {
        SearchFilter.createFilterFromString(filtStr);
      }
      catch (DirectoryException de)
      {
        isAcceptable = false;
        unacceptableReasons.add(
          ERR_PLUGIN_REFERENT_BAD_FILTER.get(filtStr, de.getMessage()));
      }
    }

    return isAcceptable;
  }

  @Override
  public boolean isConfigurationChangeAcceptable(
          ReferentialIntegrityPluginCfg configuration,
          List<LocalizableMessage> unacceptableReasons)
  {
    return isConfigurationAcceptable(configuration, unacceptableReasons);
  }

  @SuppressWarnings("unchecked")
  @Override
  public PluginResult.PostOperation
         doPostOperation(PostOperationModifyDNOperation
          modifyDNOperation)
  {
    // If the operation itself failed, then we don't need to do anything because
    // nothing changed.
    if (modifyDNOperation.getResultCode() != ResultCode.SUCCESS)
    {
      return PluginResult.PostOperation.continueOperationProcessing();
    }

    Map<DN,DN>modDNmap=
         (Map<DN, DN>) modifyDNOperation.getAttachment(MODIFYDN_DNS);
    if(modDNmap == null)
    {
      modDNmap = new LinkedHashMap<>();
      modifyDNOperation.setAttachment(MODIFYDN_DNS, modDNmap);
    }
    DN oldEntryDN=modifyDNOperation.getOriginalEntry().getName();
    DN newEntryDN=modifyDNOperation.getUpdatedEntry().getName();
    modDNmap.put(oldEntryDN, newEntryDN);

    processModifyDN(modDNmap, interval != 0);

    return PluginResult.PostOperation.continueOperationProcessing();
  }

  @SuppressWarnings("unchecked")
  @Override
  public PluginResult.PostOperation doPostOperation(
              PostOperationDeleteOperation deleteOperation)
  {
    // If the operation itself failed, then we don't need to do anything because
    // nothing changed.
    if (deleteOperation.getResultCode() != ResultCode.SUCCESS)
    {
      return PluginResult.PostOperation.continueOperationProcessing();
    }

    Set<DN> deleteDNset =
         (Set<DN>) deleteOperation.getAttachment(DELETE_DNS);
    if(deleteDNset == null)
    {
      deleteDNset = new HashSet<>();
      deleteOperation.setAttachment(MODIFYDN_DNS, deleteDNset);
    }
    deleteDNset.add(deleteOperation.getEntryDN());

    processDelete(deleteDNset, interval != 0);
    return PluginResult.PostOperation.continueOperationProcessing();
  }

  @SuppressWarnings("unchecked")
  @Override
  public PluginResult.SubordinateModifyDN processSubordinateModifyDN(
          SubordinateModifyDNOperation modifyDNOperation, Entry oldEntry,
          Entry newEntry, List<Modification> modifications)
  {
    //This cast gives an unchecked cast warning, suppress it since the cast
    //is ok.
    Map<DN,DN>modDNmap=
         (Map<DN, DN>) modifyDNOperation.getAttachment(MODIFYDN_DNS);
    if(modDNmap == null)
    {
      // First time through, create the map and set it in the operation attachment.
      modDNmap = new LinkedHashMap<>();
      modifyDNOperation.setAttachment(MODIFYDN_DNS, modDNmap);
    }
    modDNmap.put(oldEntry.getName(), newEntry.getName());
    return PluginResult.SubordinateModifyDN.continueOperationProcessing();
  }

  @SuppressWarnings("unchecked")
  @Override
  public PluginResult.SubordinateDelete processSubordinateDelete(
          DeleteOperation deleteOperation, Entry entry)
  {
    // This cast gives an unchecked cast warning, suppress it since the cast is ok.
    Set<DN> deleteDNset = (Set<DN>) deleteOperation.getAttachment(DELETE_DNS);
    if(deleteDNset == null)
    {
      // First time through, create the set and set it in the operation attachment.
      deleteDNset = new HashSet<>();
      deleteOperation.setAttachment(DELETE_DNS, deleteDNset);
    }
    deleteDNset.add(entry.getName());
    return PluginResult.SubordinateDelete.continueOperationProcessing();
  }

  /**
   * Verify that the specified attribute has either a distinguished name syntax
   * or "name and optional UID" syntax.
   *
   * @param attribute The attribute to check the syntax of.
   * @return  Returns <code>true</code> if the attribute has a valid syntax.
   */
  private boolean isAttributeSyntaxValid(AttributeType attribute)
  {
    return attribute.getSyntax().getOID().equals(SYNTAX_DN_OID) ||
            attribute.getSyntax().getOID().equals(SYNTAX_NAME_AND_OPTIONAL_UID_OID);
  }

  /**
   * Process the specified new interval value. This processing depends on what
   * the current interval value is and new value will be. The values have been
   * checked for equality at this point and are not equal.
   *
   * If the old interval is 0, then the server is in foreground mode and
   * the background thread needs to be started using the new interval value.
   *
   * If the new interval value is 0, the the server is in background mode
   * and the the background thread needs to be stopped.
   *
   * If the user just wants to change the interval value, the background thread
   * needs to be interrupted so that it can use the new interval value.
   *
   * @param newInterval The new interval value to use.
   *
   * @param msgs An array list of messages that thread stop and start messages
   *             can be added to.
   */
  private void processIntervalChange(long newInterval, List<LocalizableMessage> msgs)
  {
    if(interval == 0) {
      DirectoryServer.registerShutdownListener(this);
      interval=newInterval;
      msgs.add(INFO_PLUGIN_REFERENT_BACKGROUND_PROCESSING_STARTING.get(interval));
      setUpBackGroundProcessing();
    } else if(newInterval == 0) {
      LocalizableMessage message=
              INFO_PLUGIN_REFERENT_BACKGROUND_PROCESSING_STOPPING.get();
      msgs.add(message);
      processServerShutdown(message);
      interval=newInterval;
    } else {
      interval=newInterval;
      backGroundThread.interrupt();
      msgs.add(INFO_PLUGIN_REFERENT_BACKGROUND_PROCESSING_UPDATE_INTERVAL_CHANGED.get(interval, newInterval));
    }
  }

  /**
   * Process a modify DN post operation using the specified map of old and new
   * entry DNs.  The boolean "log" is used to determine if the  map
   * is written to the log file for the background thread to pick up. If the
   * map is to be processed in foreground, than each base DN or public
   * naming context (if the base DN configuration is empty) is processed.
   *
   * @param modDNMap  The map of old entry and new entry DNs from the modify
   *                  DN operation.
   *
   * @param log Set to <code>true</code> if the map should be written to a log
   *            file so that the background thread can process the changes at
   *            a later time.
   */
  private void processModifyDN(Map<DN, DN> modDNMap, boolean log)
  {
    if(modDNMap != null)
    {
      if(log)
      {
        writeLog(modDNMap);
      }
      else
      {
        for(DN baseDN : getBaseDNsToSearch())
        {
          doBaseDN(baseDN, modDNMap);
        }
      }
    }
  }

  /**
   * Used by both the background thread and the delete post operation to
   * process a delete operation on the specified entry DN.  The
   * boolean "log" is used to determine if the DN is written to the log file
   * for the background thread to pick up. This value is set to false if the
   * background thread is processing changes. If this method is being called
   * by a delete post operation, then setting the "log" value to false will
   * cause the DN to be processed in foreground
   * <p>
   * If the DN is to be processed, than each base DN or public naming
   * context (if the base DN configuration is empty) is checked to see if
   * entries under it contain references to the deleted entry DN that need
   * to be removed.
   *
   * @param entryDN  The DN of the deleted entry.
   *
   * @param log Set to <code>true</code> if the DN should be written to a log
   *            file so that the background thread can process the change at
   *            a later time.
   */
  private void processDelete(Set<DN> deleteDNset, boolean log)
  {
    if(log)
    {
      writeLog(deleteDNset);
    }
    else
    {
      for(DN baseDN : getBaseDNsToSearch())
      {
        doBaseDN(baseDN, deleteDNset);
      }
    }
  }

  /**
   * Used by the background thread to process the specified old entry DN and
   * new entry DN. Each base DN or public naming context (if the base DN
   * configuration is empty) is checked to see  if they contain entries with
   * references to the old entry DN that need to be changed to the new entry DN.
   *
   * @param oldEntryDN  The entry DN before the modify DN operation.
   *
   * @param newEntryDN The entry DN after the modify DN operation.
   */
  private void processModifyDN(DN oldEntryDN, DN newEntryDN)
  {
    for(DN baseDN : getBaseDNsToSearch())
    {
      searchBaseDN(baseDN, oldEntryDN, newEntryDN);
    }
  }

  /**
   * Return a set of DNs that are used to search for references under. If the
   * base DN configuration set is empty, then the public naming contexts
   * are used.
   *
   * @return A set of DNs to use in the reference searches.
   */
  private Set<DN> getBaseDNsToSearch()
  {
    if (baseDNs.isEmpty())
    {
      return DirectoryServer.getPublicNamingContexts().keySet();
    }
    return baseDNs;
  }

  /**
   * Search a base DN using a filter built from the configured attribute
   * types and the specified old entry DN. For each entry that is found from
   * the search, delete the old entry DN from the entry. If the new entry
   * DN is not null, then add it to the entry.
   *
   * @param baseDN  The DN to base the search at.
   *
   * @param oldEntryDN The old entry DN that needs to be deleted or replaced.
   *
   * @param newEntryDN The new entry DN that needs to be added. May be null
   *                   if the original operation was a delete.
   */
  private void searchBaseDN(DN baseDN, DN oldEntryDN, DN newEntryDN)
  {
    //Build an equality search with all of the configured attribute types
    //and the old entry DN.
    HashSet<SearchFilter> componentFilters=new HashSet<>();
    for(AttributeType attributeType : attributeTypes)
    {
      componentFilters.add(SearchFilter.createEqualityFilter(attributeType,
          ByteString.valueOfUtf8(oldEntryDN.toString())));
    }

    SearchFilter orFilter = SearchFilter.createORFilter(componentFilters);
    final SearchRequest request = newSearchRequest(baseDN, SearchScope.WHOLE_SUBTREE, orFilter);
    InternalSearchOperation operation = getRootConnection().processSearch(request);

    switch (operation.getResultCode().asEnum())
    {
      case SUCCESS:
        break;

      case NO_SUCH_OBJECT:
        logger.debug(INFO_PLUGIN_REFERENT_SEARCH_NO_SUCH_OBJECT, baseDN);
        return;

      default:
        logger.error(ERR_PLUGIN_REFERENT_SEARCH_FAILED, operation.getErrorMessage());
        return;
    }

    for (SearchResultEntry entry : operation.getSearchEntries())
    {
      deleteAddAttributesEntry(entry, oldEntryDN, newEntryDN);
    }
  }

  /**
   * This method is used in foreground processing of a modify DN operation.
   * It uses the specified map to perform base DN searching for each map
   * entry. The key is the old entry DN and the value is the
   * new entry DN.
   *
   * @param baseDN The DN to base the search at.
   *
   * @param modifyDNmap The map containing the modify DN old and new entry DNs.
   */
  private void doBaseDN(DN baseDN, Map<DN,DN> modifyDNmap)
  {
    for(Map.Entry<DN,DN> mapEntry: modifyDNmap.entrySet())
    {
      searchBaseDN(baseDN, mapEntry.getKey(), mapEntry.getValue());
    }
  }

  /**
   * This method is used in foreground processing of a delete operation.
   * It uses the specified set to perform base DN searching for each
   * element.
   *
   * @param baseDN The DN to base the search at.
   *
   * @param deleteDNset The set containing the delete DNs.
   */
  private void doBaseDN(DN baseDN, Set<DN> deleteDNset)
  {
    for(DN deletedEntryDN : deleteDNset)
    {
      searchBaseDN(baseDN, deletedEntryDN, null);
    }
  }

  /**
   * For each attribute type, delete the specified old entry DN and
   * optionally add the specified new entry DN if the DN is not null.
   * The specified entry is used to see if it contains each attribute type so
   * those types that the entry contains can be modified. An internal modify
   * is performed to change the entry.
   *
   * @param e The entry that contains the old references.
   *
   * @param oldEntryDN The old entry DN to remove references to.
   *
   * @param newEntryDN The new entry DN to add a reference to, if it is not
   *                   null.
   */
  private void deleteAddAttributesEntry(Entry e, DN oldEntryDN, DN newEntryDN)
  {
    LinkedList<Modification> mods = new LinkedList<>();
    DN entryDN=e.getName();
    for(AttributeType type : attributeTypes)
    {
      if(e.hasAttribute(type))
      {
        ByteString value = ByteString.valueOfUtf8(oldEntryDN.toString());
        if (e.hasValue(type, value))
        {
          mods.add(new Modification(ModificationType.DELETE, Attributes
              .create(type, value)));

          // If the new entry DN exists, create an ADD modification for it.
          if(newEntryDN != null)
          {
            mods.add(new Modification(ModificationType.ADD, Attributes
                .create(type, newEntryDN.toString())));
          }
        }
      }
    }

    InternalClientConnection conn =
            InternalClientConnection.getRootConnection();
    ModifyOperation modifyOperation =
            conn.processModify(entryDN, mods);
    if(modifyOperation.getResultCode() != ResultCode.SUCCESS)
    {
      logger.error(ERR_PLUGIN_REFERENT_MODIFY_FAILED, entryDN, modifyOperation.getErrorMessage());
    }
  }

  /**
   * Sets up the log file that the plugin can write update recored to and
   * the background thread can use to read update records from. The specified
   * log file name is the name to use for the file. If the file exists from
   * a previous run, use it.
   *
   * @param logFileName The name of the file to use, may be absolute.
   *
   * @throws ConfigException If a new file cannot be created if needed.
   */
  private void setUpLogFile(String logFileName)
          throws ConfigException
  {
    this.logFileName=logFileName;
    logFile=getFileForPath(logFileName);

    try
    {
      if(!logFile.exists())
      {
        logFile.createNewFile();
      }
    }
    catch (IOException io)
    {
      throw new ConfigException(ERR_PLUGIN_REFERENT_CREATE_LOGFILE.get(
                                     io.getMessage()), io);
    }
  }

  /**
   * Returns a buffered writer that the plugin can use to write update records with.
   *
   * @throws IOException If a new file writer cannot be created.
   */
  private BufferedWriter setupWriter() throws IOException {
    return new BufferedWriter(new FileWriter(logFile, true));
  }

  /**
   * Write the specified map of old entry and new entry DNs to the log
   * file. Each entry of the map is a line in the file, the key is the old
   * entry normalized DN and the value is the new entry normalized DN.
   * The DNs are separated by the tab character. This map is related to a
   * modify DN operation.
   *
   * @param modDNmap The map of old entry and new entry DNs.
   */
  private void writeLog(Map<DN,DN> modDNmap) {
    synchronized(logFile)
    {
      try (BufferedWriter writer = setupWriter())
      {
        for(Map.Entry<DN,DN> mapEntry : modDNmap.entrySet())
        {
          writer.write(mapEntry.getKey() + "\t" + mapEntry.getValue());
          writer.newLine();
        }
      }
      catch (IOException io)
      {
        logger.error(ERR_PLUGIN_REFERENT_CLOSE_LOGFILE, io.getMessage());
      }
    }
  }

  /**
   * Write the specified entry DNs to the log file.
   * These entry DNs are related to a delete operation.
   *
   * @param deletedEntryDN The DN of the deleted entry.
   */
  private void writeLog(Set<DN> deleteDNset) {
    synchronized(logFile)
    {
      try (BufferedWriter writer = setupWriter())
      {
        for (DN deletedEntryDN : deleteDNset)
        {
          writer.write(deletedEntryDN.toString());
          writer.newLine();
        }
      }
      catch (IOException io)
      {
        logger.error(ERR_PLUGIN_REFERENT_CLOSE_LOGFILE, io.getMessage());
      }
    }
  }

  /**
   * Process all of the records in the log file. Each line of the file is read
   * and parsed to determine if it was a delete operation (a single normalized
   * DN) or a modify DN operation (two normalized DNs separated by a tab). The
   * corresponding operation method is called to perform the referential
   * integrity processing as though the operation was just processed. After
   * all of the records in log file have been processed, the log file is
   * cleared so that new records can be added.
   */
  private void processLog() {
    synchronized(logFile) {
      try {
        if(logFile.length() == 0)
        {
          return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile)))
        {
          String line;
          while((line=reader.readLine()) != null) {
            try {
              String[] a=line.split("[\t]");
              DN origDn = DN.valueOf(a[0]);
              //If there is only a single DN string than it must be a delete.
              if(a.length == 1) {
                processDelete(Collections.singleton(origDn), false);
              } else {
                DN movedDN=DN.valueOf(a[1]);
                processModifyDN(origDn, movedDN);
              }
            } catch (LocalizedIllegalArgumentException e) {
              //This exception should rarely happen since the plugin wrote the DN
              //strings originally.
              logger.error(ERR_PLUGIN_REFERENT_CANNOT_DECODE_STRING_AS_DN, e.getMessage());
            }
          }
        }
        logFile.delete();
        logFile.createNewFile();
      } catch (IOException io) {
        logger.error(ERR_PLUGIN_REFERENT_REPLACE_LOGFILE, io.getMessage());
      }
    }
  }

  /**
   * Return the listener name.
   *
   * @return The name of the listener.
   */
  @Override
  public String getShutdownListenerName() {
    return name;
  }

  @Override
  public final void finalizePlugin() {
    currentConfiguration.removeReferentialIntegrityChangeListener(this);
    if(interval > 0)
    {
      processServerShutdown(null);
    }
  }

  /**
   * Process a server shutdown. If the background thread is running it needs
   * to be interrupted so it can read the stop request variable and exit.
   *
   * @param reason The reason message for the shutdown.
   */
  @Override
  public void processServerShutdown(LocalizableMessage reason)
  {
    stopRequested = true;

    // Wait for back ground thread to terminate
    while (backGroundThread != null && backGroundThread.isAlive()) {
      try {
        // Interrupt if its sleeping
        backGroundThread.interrupt();
        backGroundThread.join();
      }
      catch (InterruptedException ex) {
        //Expected.
      }
    }
    DirectoryServer.deregisterShutdownListener(this);
    backGroundThread=null;
  }


  /**
   * Returns the interval time converted to milliseconds.
   *
   * @return The interval time for the background thread.
   */
  private long getInterval() {
    return interval * 1000;
  }

  /**
   * Sets up background processing of referential integrity by creating a
   * new background thread to process updates.
   */
  private void setUpBackGroundProcessing()  {
    if(backGroundThread == null) {
      DirectoryServer.registerShutdownListener(this);
      stopRequested = false;
      backGroundThread = new BackGroundThread();
      backGroundThread.start();
    }
  }


  /**
   * Used by the background thread to determine if it should exit.
   *
   * @return Returns <code>true</code> if the background thread should exit.
   */
  private boolean isShuttingDown()  {
    return stopRequested;
  }

  /**
   * The background referential integrity processing thread. Wakes up after
   * sleeping for a configurable interval and checks the log file for update
   * records.
   */
  private class BackGroundThread extends DirectoryThread {

    /** Constructor for the background thread. */
    public
    BackGroundThread() {
      super(name);
    }

    /** Run method for the background thread. */
    @Override
    public void run() {
      while(!isShuttingDown())  {
        try {
          sleep(getInterval());
        } catch(InterruptedException e) {
          continue;
        } catch(Exception e) {
          logger.traceException(e);
        }
        processLog();
      }
    }
  }

  @Override
  public PluginResult.PreOperation doPreOperation(
    PreOperationModifyOperation modifyOperation)
  {
    /* Skip the integrity checks if the enforcing is not enabled */

    if (!currentConfiguration.isCheckReferences())
    {
      return PluginResult.PreOperation.continueOperationProcessing();
    }

    final List<Modification> mods = modifyOperation.getModifications();
    final Entry entry = modifyOperation.getModifiedEntry();

    /* Make sure the entry belongs to one of the configured naming contexts. */
    DN entryDN = entry.getName();
    DN entryBaseDN = getEntryBaseDN(entryDN);
    if (entryBaseDN == null)
    {
      return PluginResult.PreOperation.continueOperationProcessing();
    }

    for (Modification mod : mods)
    {
      final ModificationType modType = mod.getModificationType();

      /* Process only ADD and REPLACE modification types. */
      if (modType != ModificationType.ADD
          && modType != ModificationType.REPLACE)
      {
        break;
      }

      Attribute modifiedAttribute = entry.getExactAttribute(mod.getAttribute().getAttributeDescription());
      if (modifiedAttribute != null)
      {
        PluginResult.PreOperation result =
        isIntegrityMaintained(modifiedAttribute, entryDN, entryBaseDN);
        if (result.getResultCode() != ResultCode.SUCCESS)
        {
          return result;
        }
      }
    }

    /* At this point, everything is fine. */
    return PluginResult.PreOperation.continueOperationProcessing();
  }

  @Override
  public PluginResult.PreOperation doPreOperation(PreOperationAddOperation addOperation)
  {
    // Skip the integrity checks if the enforcing is not enabled.
    if (!currentConfiguration.isCheckReferences())
    {
      return PluginResult.PreOperation.continueOperationProcessing();
    }

    final Entry entry = addOperation.getEntryToAdd();

    // Make sure the entry belongs to one of the configured naming contexts.
    DN entryDN = entry.getName();
    DN entryBaseDN = getEntryBaseDN(entryDN);
    if (entryBaseDN == null)
    {
      return PluginResult.PreOperation.continueOperationProcessing();
    }

    for (AttributeType attrType : attributeTypes)
    {
      final List<Attribute> attrs = entry.getAttribute(attrType, false);
      PluginResult.PreOperation result = isIntegrityMaintained(attrs, entryDN, entryBaseDN);
      if (result.getResultCode() != ResultCode.SUCCESS)
      {
        return result;
      }
    }

    return PluginResult.PreOperation.continueOperationProcessing();
  }

  /**
   * Verifies that the integrity of values is maintained.
   * @param attrs   Attribute list which refers to another entry in the
   *                directory.
   * @param entryDN DN of the entry which contains the <CODE>attr</CODE>
   *                attribute.
   * @return        The SUCCESS if the integrity is maintained or
   *                CONSTRAINT_VIOLATION oherwise
   */
  private PluginResult.PreOperation
    isIntegrityMaintained(List<Attribute> attrs, DN entryDN, DN entryBaseDN)
  {
    for(Attribute attr : attrs)
    {
      PluginResult.PreOperation result =
          isIntegrityMaintained(attr, entryDN, entryBaseDN);
      if (result != PluginResult.PreOperation.continueOperationProcessing())
      {
        return result;
      }
    }

    return PluginResult.PreOperation.continueOperationProcessing();
  }

  /**
   * Verifies that the integrity of values is maintained.
   * @param attr    Attribute which refers to another entry in the
   *                directory.
   * @param entryDN DN of the entry which contains the <CODE>attr</CODE>
   *                attribute.
   * @return        The SUCCESS if the integrity is maintained or
   *                CONSTRAINT_VIOLATION otherwise
   */
  private PluginResult.PreOperation isIntegrityMaintained(Attribute attr, DN entryDN, DN entryBaseDN)
  {
    try
    {
      AttributeDescription attrDesc = attr.getAttributeDescription();
      for (ByteString attrVal : attr)
      {
        DN valueEntryDN = DN.valueOf(attrVal);

        final Entry valueEntry;
        if (currentConfiguration.getCheckReferencesScopeCriteria() == CheckReferencesScopeCriteria.NAMING_CONTEXT
            && valueEntryDN.isInScopeOf(entryBaseDN, SearchScope.SUBORDINATES))
        {
          return PluginResult.PreOperation.stopProcessing(ResultCode.CONSTRAINT_VIOLATION,
              ERR_PLUGIN_REFERENT_NAMINGCONTEXT_MISMATCH.get(valueEntryDN, attrDesc, entryDN));
        }
        valueEntry = DirectoryServer.getEntry(valueEntryDN);

        // Verify that the value entry exists in the backend.
        if (valueEntry == null)
        {
          return PluginResult.PreOperation.stopProcessing(ResultCode.CONSTRAINT_VIOLATION,
            ERR_PLUGIN_REFERENT_ENTRY_MISSING.get(valueEntryDN, attrDesc, entryDN));
        }

        // Verify that the value entry conforms to the filter.
        SearchFilter filter = attrFiltMap.get(attrDesc.getAttributeType());
        if (filter != null && !filter.matchesEntry(valueEntry))
        {
          return PluginResult.PreOperation.stopProcessing(ResultCode.CONSTRAINT_VIOLATION,
            ERR_PLUGIN_REFERENT_FILTER_MISMATCH.get(valueEntry.getName(), attrDesc, entryDN, filter));
        }
      }
    }
    catch (Exception de)
    {
      return PluginResult.PreOperation.stopProcessing(ResultCode.OTHER,
        ERR_PLUGIN_REFERENT_EXCEPTION.get(de.getLocalizedMessage()));
    }

    return PluginResult.PreOperation.continueOperationProcessing();
  }

  /**
   * Verifies if the entry with the specified DN belongs to the
   * configured naming contexts.
   * @param dn DN of the entry.
   * @return Returns <code>true</code> if the entry matches any of the
   *         configured base DNs, and <code>false</code> if not.
   */
  private DN getEntryBaseDN(DN dn)
  {
    /* Verify that the entry belongs to one of the configured naming contexts. */

    DN namingContext = null;

    if (baseDNs.isEmpty())
    {
      baseDNs = DirectoryServer.getPublicNamingContexts().keySet();
    }

    for (DN baseDN : baseDNs)
    {
      if (dn.isInScopeOf(baseDN, SearchScope.SUBORDINATES))
      {
        namingContext = baseDN;
        break;
      }
    }

    return namingContext;
  }
}
