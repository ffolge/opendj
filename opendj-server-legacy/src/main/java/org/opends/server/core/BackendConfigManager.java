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
 * Copyright 2006-2008 Sun Microsystems, Inc.
 * Portions Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.server.core;

import static org.forgerock.opendj.ldap.ResultCode.*;
import static org.opends.messages.ConfigMessages.*;
import static org.opends.server.core.DirectoryServer.*;
import static org.opends.server.util.StaticUtils.*;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.config.server.ConfigChangeResult;
import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.config.server.ConfigurationAddListener;
import org.forgerock.opendj.config.server.ConfigurationChangeListener;
import org.forgerock.opendj.config.server.ConfigurationDeleteListener;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.server.config.meta.BackendCfgDefn;
import org.forgerock.opendj.server.config.server.BackendCfg;
import org.forgerock.opendj.server.config.server.RootCfg;
import org.opends.server.api.Backend;
import org.opends.server.api.BackendInitializationListener;
import org.opends.server.backends.ConfigurationBackend;
import org.opends.server.config.ConfigConstants;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.Entry;
import org.opends.server.types.InitializationException;
import org.opends.server.types.WritabilityMode;

/**
 * This class defines a utility that will be used to manage the configuration
 * for the set of backends defined in the Directory Server.  It will perform
 * the necessary initialization of those backends when the server is first
 * started, and then will manage any changes to them while the server is
 * running.
 */
public class BackendConfigManager implements
     ConfigurationChangeListener<BackendCfg>,
     ConfigurationAddListener<BackendCfg>,
     ConfigurationDeleteListener<BackendCfg>
{
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  /** The mapping between configuration entry DNs and their corresponding backend implementations. */
  private final ConcurrentHashMap<DN, Backend<? extends BackendCfg>> registeredBackends = new ConcurrentHashMap<>();
  private final ServerContext serverContext;

  /**
   * Creates a new instance of this backend config manager.
   *
   * @param serverContext
   *            The server context.
   */
  public BackendConfigManager(ServerContext serverContext)
  {
    this.serverContext = serverContext;
  }

  /**
   * Initializes the configuration associated with the Directory Server
   * backends. This should only be called at Directory Server startup.
   *
   * @param backendIDsToStart
   *           The list of backendID to start. Everything will be started if empty.
   * @throws ConfigException
   *           If a critical configuration problem prevents the backend
   *           initialization from succeeding.
   * @throws InitializationException
   *           If a problem occurs while initializing the backends that is not
   *           related to the server configuration.
   */
  public void initializeBackendConfig(Collection<String> backendIDsToStart)
         throws ConfigException, InitializationException
  {
    initializeConfigurationBackend();

    // Register add and delete listeners.
    RootCfg root = serverContext.getRootConfig();
    root.addBackendAddListener(this);
    root.addBackendDeleteListener(this);

    // Get the configuration entry that is at the root of all the backends in
    // the server.
    Entry backendRoot;
    try
    {
      DN configEntryDN = DN.valueOf(ConfigConstants.DN_BACKEND_BASE);
      backendRoot   = DirectoryServer.getConfigEntry(configEntryDN);
    }
    catch (Exception e)
    {
      logger.traceException(e);

      LocalizableMessage message =
          ERR_CONFIG_BACKEND_CANNOT_GET_CONFIG_BASE.get(getExceptionMessage(e));
      throw new ConfigException(message, e);
    }


    // If the configuration root entry is null, then assume it doesn't exist.
    // In that case, then fail.  At least that entry must exist in the
    // configuration, even if there are no backends defined below it.
    if (backendRoot == null)
    {
      throw new ConfigException(ERR_CONFIG_BACKEND_BASE_DOES_NOT_EXIST.get());
    }


    // Initialize existing backends.
    for (String name : root.listBackends())
    {
      // Get the handler's configuration.
      // This will decode and validate its properties.
      final BackendCfg backendCfg = root.getBackend(name);
      final DN backendDN = backendCfg.dn();
      final String backendID = backendCfg.getBackendId();
      if (!backendIDsToStart.isEmpty() && !backendIDsToStart.contains(backendID))
      {
        continue;
      }

      // Register as a change listener for this backend so that we can be
      // notified when it is disabled or enabled.
      backendCfg.addChangeListener(this);

      if (!backendCfg.isEnabled())
      {
        logger.debug(INFO_CONFIG_BACKEND_DISABLED, backendDN);
        continue;
      }
      else if (DirectoryServer.hasBackend(backendID))
      {
        logger.warn(WARN_CONFIG_BACKEND_DUPLICATE_BACKEND_ID, backendID, backendDN);
        continue;
      }

      // See if the entry contains an attribute that specifies the class name
      // for the backend implementation.  If it does, then load it and make
      // sure that it's a valid backend implementation.  There is no such
      // attribute, the specified class cannot be loaded, or it does not
      // contain a valid backend implementation, then log an error and skip it.
      String className = backendCfg.getJavaClass();

      Backend<? extends BackendCfg> backend;
      try
      {
        backend = loadBackendClass(className).newInstance();
      }
      catch (Exception e)
      {
        logger.traceException(e);
        logger.error(ERR_CONFIG_BACKEND_CANNOT_INSTANTIATE, className, backendDN, stackTraceToSingleLineString(e));
        continue;
      }

      initializeBackend(backend, backendCfg);
    }
  }

  private void initializeConfigurationBackend() throws InitializationException
  {
    final ConfigurationBackend configBackend =
        new ConfigurationBackend(serverContext, DirectoryServer.getConfigurationHandler());
    initializeBackend(configBackend, configBackend.getBackendCfg());
  }

  private void initializeBackend(Backend<? extends BackendCfg> backend, BackendCfg backendCfg)
  {
    ConfigChangeResult ccr = new ConfigChangeResult();
    initializeBackend(backend, backendCfg, ccr);
    for (LocalizableMessage msg : ccr.getMessages())
    {
      logger.error(msg);
    }
  }

  private void initializeBackend(Backend<? extends BackendCfg> backend, BackendCfg backendCfg, ConfigChangeResult ccr)
  {
    backend.setBackendID(backendCfg.getBackendId());
    backend.setWritabilityMode(toWritabilityMode(backendCfg.getWritabilityMode()));

    if (acquireSharedLock(backend, backendCfg.getBackendId(), ccr) && configureAndOpenBackend(backend, backendCfg, ccr))
    {
      registerBackend(backend, backendCfg, ccr);
    }
  }

  /**
   * Acquire a shared lock on this backend. This will prevent operations like LDIF import or restore
   * from occurring while the backend is active.
   */
  private boolean acquireSharedLock(Backend<?> backend, String backendID, final ConfigChangeResult ccr)
  {
    try
    {
      String lockFile = LockFileManager.getBackendLockFileName(backend);
      StringBuilder failureReason = new StringBuilder();
      if (!LockFileManager.acquireSharedLock(lockFile, failureReason))
      {
        cannotAcquireLock(backendID, ccr, failureReason);
        return false;
      }
      return true;
    }
    catch (Exception e)
    {
      logger.traceException(e);

      cannotAcquireLock(backendID, ccr, stackTraceToSingleLineString(e));
      return false;
    }
  }

  private void cannotAcquireLock(String backendID, final ConfigChangeResult ccr, CharSequence failureReason)
  {
    LocalizableMessage message = ERR_CONFIG_BACKEND_CANNOT_ACQUIRE_SHARED_LOCK.get(backendID, failureReason);
    logger.error(message);

    // FIXME -- Do we need to send an admin alert?
    ccr.setResultCode(ResultCode.CONSTRAINT_VIOLATION);
    ccr.setAdminActionRequired(true);
    ccr.addMessage(message);
  }

  private void releaseSharedLock(Backend<?> backend, String backendID)
  {
    try
    {
      String lockFile = LockFileManager.getBackendLockFileName(backend);
      StringBuilder failureReason = new StringBuilder();
      if (! LockFileManager.releaseLock(lockFile, failureReason))
      {
        logger.warn(WARN_CONFIG_BACKEND_CANNOT_RELEASE_SHARED_LOCK, backendID, failureReason);
        // FIXME -- Do we need to send an admin alert?
      }
    }
    catch (Exception e2)
    {
      logger.traceException(e2);

      logger.warn(WARN_CONFIG_BACKEND_CANNOT_RELEASE_SHARED_LOCK, backendID, stackTraceToSingleLineString(e2));
      // FIXME -- Do we need to send an admin alert?
    }
  }

  @Override
  public boolean isConfigurationChangeAcceptable(
       BackendCfg configEntry,
       List<LocalizableMessage> unacceptableReason)
  {
    DN backendDN = configEntry.dn();


    Set<DN> baseDNs = configEntry.getBaseDN();

    // See if the backend is registered with the server.  If it is, then
    // see what's changed and whether those changes are acceptable.
    Backend<?> backend = registeredBackends.get(backendDN);
    if (backend != null)
    {
      LinkedHashSet<DN> removedDNs = new LinkedHashSet<>(backend.getBaseDNs());
      LinkedHashSet<DN> addedDNs = new LinkedHashSet<>(baseDNs);
      Iterator<DN> iterator = removedDNs.iterator();
      while (iterator.hasNext())
      {
        DN dn = iterator.next();
        if (addedDNs.remove(dn))
        {
          iterator.remove();
        }
      }

      // Copy the directory server's base DN registry and make the
      // requested changes to see if it complains.
      BaseDnRegistry reg = DirectoryServer.copyBaseDnRegistry();
      for (DN dn : removedDNs)
      {
        try
        {
          reg.deregisterBaseDN(dn);
        }
        catch (DirectoryException de)
        {
          logger.traceException(de);

          unacceptableReason.add(de.getMessageObject());
          return false;
        }
      }

      for (DN dn : addedDNs)
      {
        try
        {
          reg.registerBaseDN(dn, backend, false);
        }
        catch (DirectoryException de)
        {
          logger.traceException(de);

          unacceptableReason.add(de.getMessageObject());
          return false;
        }
      }
    }
    else if (configEntry.isEnabled())
    {
      /*
       * If the backend was not enabled, it has not been registered with directory server, so
       * no listeners will be registered at the lower layers. Verify as it was an add.
       */
      String className = configEntry.getJavaClass();
      try
      {
        Class<Backend<BackendCfg>> backendClass = loadBackendClass(className);
        if (! Backend.class.isAssignableFrom(backendClass))
        {
          unacceptableReason.add(ERR_CONFIG_BACKEND_CLASS_NOT_BACKEND.get(className, backendDN));
          return false;
        }

        Backend<BackendCfg> b = backendClass.newInstance();
        if (! b.isConfigurationAcceptable(configEntry, unacceptableReason, serverContext))
        {
          return false;
        }
      }
      catch (Exception e)
      {
        logger.traceException(e);
        unacceptableReason.add(
            ERR_CONFIG_BACKEND_CANNOT_INSTANTIATE.get(className, backendDN, stackTraceToSingleLineString(e)));
        return false;
      }
    }

    // If we've gotten to this point, then it is acceptable as far as we are
    // concerned.  If it is unacceptable according to the configuration for that
    // backend, then the backend itself will need to make that determination.
    return true;
  }

  @Override
  public ConfigChangeResult applyConfigurationChange(BackendCfg cfg)
  {
    DN backendDN = cfg.dn();
    Backend<? extends BackendCfg> backend = registeredBackends.get(backendDN);
    final ConfigChangeResult ccr = new ConfigChangeResult();

    // See if the entry contains an attribute that indicates whether the
    // backend should be enabled.
    boolean needToEnable = false;
    try
    {
      if (cfg.isEnabled())
      {
        // The backend is marked as enabled.  See if that is already true.
        if (backend == null)
        {
          needToEnable = true;
        } // else already enabled, no need to do anything.
      }
      else
      {
        // The backend is marked as disabled.  See if that is already true.
        if (backend != null)
        {
          // It isn't disabled, so we will do so now and deregister it from the
          // Directory Server.
          deregisterBackend(backendDN, backend);

          backend.finalizeBackend();

          releaseSharedLock(backend, backend.getBackendID());

          return ccr;
        } // else already disabled, no need to do anything.
      }
    }
    catch (Exception e)
    {
      logger.traceException(e);

      ccr.setResultCode(DirectoryServer.getServerErrorResultCode());
      ccr.addMessage(ERR_CONFIG_BACKEND_UNABLE_TO_DETERMINE_ENABLED_STATE.get(backendDN,
          stackTraceToSingleLineString(e)));
      return ccr;
    }

    // See if the entry contains an attribute that specifies the class name
    // for the backend implementation.  If it does, then load it and make sure
    // that it's a valid backend implementation.  There is no such attribute,
    // the specified class cannot be loaded, or it does not contain a valid
    // backend implementation, then log an error and skip it.
    String className = cfg.getJavaClass();

    // See if this backend is currently active and if so if the name of the class is the same.
    if (backend != null && !className.equals(backend.getClass().getName()))
    {
      // It is not the same. Try to load it and see if it is a valid backend implementation.
      try
      {
        Class<?> backendClass = DirectoryServer.loadClass(className);
        if (Backend.class.isAssignableFrom(backendClass))
        {
          // It appears to be a valid backend class.  We'll return that the
          // change is successful, but indicate that some administrative
          // action is required.
          ccr.addMessage(NOTE_CONFIG_BACKEND_ACTION_REQUIRED_TO_CHANGE_CLASS.get(
              backendDN, backend.getClass().getName(), className));
          ccr.setAdminActionRequired(true);
        }
        else
        {
          // It is not a valid backend class.  This is an error.
          ccr.setResultCode(ResultCode.CONSTRAINT_VIOLATION);
          ccr.addMessage(ERR_CONFIG_BACKEND_CLASS_NOT_BACKEND.get(className, backendDN));
        }
        return ccr;
      }
      catch (Exception e)
      {
        logger.traceException(e);

        ccr.setResultCode(DirectoryServer.getServerErrorResultCode());
        ccr.addMessage(ERR_CONFIG_BACKEND_CANNOT_INSTANTIATE.get(
                className, backendDN, stackTraceToSingleLineString(e)));
        return ccr;
      }
    }


    // If we've gotten here, then that should mean that we need to enable the
    // backend.  Try to do so.
    if (needToEnable)
    {
      try
      {
        backend = loadBackendClass(className).newInstance();
      }
      catch (Exception e)
      {
        // It is not a valid backend class.  This is an error.
        ccr.setResultCode(ResultCode.CONSTRAINT_VIOLATION);
        ccr.addMessage(ERR_CONFIG_BACKEND_CLASS_NOT_BACKEND.get(className, backendDN));
        return ccr;
      }

      initializeBackend(backend, cfg, ccr);
      return ccr;
    }
    else if (ccr.getResultCode() == ResultCode.SUCCESS && backend != null)
    {
      backend.setWritabilityMode(toWritabilityMode(cfg.getWritabilityMode()));
    }

    return ccr;
  }

  private boolean registerBackend(Backend<? extends BackendCfg> backend, BackendCfg backendCfg, ConfigChangeResult ccr)
  {
    for (BackendInitializationListener listener : getBackendInitializationListeners())
    {
      listener.performBackendPreInitializationProcessing(backend);
    }

    try
    {
      DirectoryServer.registerBackend(backend);
    }
    catch (Exception e)
    {
      logger.traceException(e);

      LocalizableMessage message =
          WARN_CONFIG_BACKEND_CANNOT_REGISTER_BACKEND.get(backendCfg.getBackendId(), getExceptionMessage(e));
      logger.error(message);

      // FIXME -- Do we need to send an admin alert?
      ccr.setResultCode(DirectoryServer.getServerErrorResultCode());
      ccr.addMessage(message);
      return false;
    }

    for (BackendInitializationListener listener : getBackendInitializationListeners())
    {
      listener.performBackendPostInitializationProcessing(backend);
    }

    registeredBackends.put(backendCfg.dn(), backend);
    return true;
  }

  @Override
  public boolean isConfigurationAddAcceptable(
       BackendCfg configEntry,
       List<LocalizableMessage> unacceptableReason)
  {
    DN backendDN = configEntry.dn();


    // See if the entry contains an attribute that specifies the backend ID.  If
    // it does not, then skip it.
    String backendID = configEntry.getBackendId();
    if (DirectoryServer.hasBackend(backendID))
    {
      unacceptableReason.add(WARN_CONFIG_BACKEND_DUPLICATE_BACKEND_ID.get(backendDN, backendID));
      return false;
    }


    // See if the entry contains an attribute that specifies the set of base DNs
    // for the backend.  If it does not, then skip it.
    Set<DN> baseList = configEntry.getBaseDN();
    DN[] baseDNs = new DN[baseList.size()];
    baseList.toArray(baseDNs);


    // See if the entry contains an attribute that specifies the class name
    // for the backend implementation.  If it does, then load it and make sure
    // that it's a valid backend implementation.  There is no such attribute,
    // the specified class cannot be loaded, or it does not contain a valid
    // backend implementation, then log an error and skip it.
    String className = configEntry.getJavaClass();

    Backend<BackendCfg> backend;
    try
    {
      backend = loadBackendClass(className).newInstance();
    }
    catch (Exception e)
    {
      logger.traceException(e);

      unacceptableReason.add(ERR_CONFIG_BACKEND_CANNOT_INSTANTIATE.get(
              className, backendDN, stackTraceToSingleLineString(e)));
      return false;
    }


    // Make sure that all of the base DNs are acceptable for use in the server.
    BaseDnRegistry reg = DirectoryServer.copyBaseDnRegistry();
    for (DN baseDN : baseDNs)
    {
      try
      {
        reg.registerBaseDN(baseDN, backend, false);
      }
      catch (DirectoryException de)
      {
        unacceptableReason.add(de.getMessageObject());
        return false;
      }
      catch (Exception e)
      {
        unacceptableReason.add(getExceptionMessage(e));
        return false;
      }
    }

    return backend.isConfigurationAcceptable(configEntry, unacceptableReason, serverContext);
  }

  @Override
  public ConfigChangeResult applyConfigurationAdd(BackendCfg cfg)
  {
    DN                backendDN           = cfg.dn();
    final ConfigChangeResult ccr = new ConfigChangeResult();

    // Register as a change listener for this backend entry so that we will
    // be notified of any changes that may be made to it.
    cfg.addChangeListener(this);

    // See if the entry contains an attribute that indicates whether the backend should be enabled.
    // If it does not, or if it is not set to "true", then skip it.
    if (!cfg.isEnabled())
    {
      // The backend is explicitly disabled.  We will log a message to
      // indicate that it won't be enabled and return.
      LocalizableMessage message = INFO_CONFIG_BACKEND_DISABLED.get(backendDN);
      logger.debug(message);
      ccr.addMessage(message);
      return ccr;
    }



    // See if the entry contains an attribute that specifies the backend ID.  If
    // it does not, then skip it.
    String backendID = cfg.getBackendId();
    if (DirectoryServer.hasBackend(backendID))
    {
      LocalizableMessage message = WARN_CONFIG_BACKEND_DUPLICATE_BACKEND_ID.get(backendDN, backendID);
      logger.warn(message);
      ccr.addMessage(message);
      return ccr;
    }


    // See if the entry contains an attribute that specifies the class name
    // for the backend implementation.  If it does, then load it and make sure
    // that it's a valid backend implementation.  There is no such attribute,
    // the specified class cannot be loaded, or it does not contain a valid
    // backend implementation, then log an error and skip it.
    String className = cfg.getJavaClass();

    Backend<? extends BackendCfg> backend;
    try
    {
      backend = loadBackendClass(className).newInstance();
    }
    catch (Exception e)
    {
      logger.traceException(e);

      ccr.setResultCode(DirectoryServer.getServerErrorResultCode());
      ccr.addMessage(ERR_CONFIG_BACKEND_CANNOT_INSTANTIATE.get(
          className, backendDN, stackTraceToSingleLineString(e)));
      return ccr;
    }

    initializeBackend(backend, cfg, ccr);
    return ccr;
  }

  private boolean configureAndOpenBackend(Backend<?> backend, BackendCfg cfg, ConfigChangeResult ccr)
  {
    try
    {
      configureAndOpenBackend(backend, cfg);
      return true;
    }
    catch (Exception e)
    {
      logger.traceException(e);

      ccr.setResultCode(DirectoryServer.getServerErrorResultCode());
      ccr.addMessage(ERR_CONFIG_BACKEND_CANNOT_INITIALIZE.get(
          cfg.getJavaClass(), cfg.dn(), stackTraceToSingleLineString(e)));

      releaseSharedLock(backend, cfg.getBackendId());
      return false;
    }
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private void configureAndOpenBackend(Backend backend, BackendCfg cfg) throws ConfigException, InitializationException
  {
    backend.configureBackend(cfg, serverContext);
    backend.openBackend();
  }

  @SuppressWarnings("unchecked")
  private Class<Backend<BackendCfg>> loadBackendClass(String className) throws Exception
  {
    return (Class<Backend<BackendCfg>>) DirectoryServer.loadClass(className);
  }

  private WritabilityMode toWritabilityMode(BackendCfgDefn.WritabilityMode writabilityMode)
  {
    switch (writabilityMode)
    {
    case DISABLED:
      return WritabilityMode.DISABLED;
    case ENABLED:
      return WritabilityMode.ENABLED;
    case INTERNAL_ONLY:
      return WritabilityMode.INTERNAL_ONLY;
    default:
      return WritabilityMode.ENABLED;
    }
  }

  @Override
  public boolean isConfigurationDeleteAcceptable(
       BackendCfg configEntry,
       List<LocalizableMessage> unacceptableReason)
  {
    DN backendDN = configEntry.dn();


    // See if this backend config manager has a backend registered with the
    // provided DN.  If not, then we don't care if the entry is deleted.  If we
    // do know about it, then that means that it is enabled and we will not
    // allow removing a backend that is enabled.
    Backend<?> backend = registeredBackends.get(backendDN);
    if (backend == null)
    {
      return true;
    }


    // See if the backend has any subordinate backends.  If so, then it is not
    // acceptable to remove it.  Otherwise, it should be fine.
    Backend<?>[] subBackends = backend.getSubordinateBackends();
    if (subBackends != null && subBackends.length != 0)
    {
      unacceptableReason.add(NOTE_CONFIG_BACKEND_CANNOT_REMOVE_BACKEND_WITH_SUBORDINATES.get(backendDN));
      return false;
    }
    return true;
  }

  @Override
  public ConfigChangeResult applyConfigurationDelete(BackendCfg configEntry)
  {
    DN                backendDN           = configEntry.dn();
    final ConfigChangeResult ccr = new ConfigChangeResult();

    // See if this backend config manager has a backend registered with the
    // provided DN.  If not, then we don't care if the entry is deleted.
    Backend<?> backend = registeredBackends.get(backendDN);
    if (backend == null)
    {
      return ccr;
    }

    // See if the backend has any subordinate backends.  If so, then it is not
    // acceptable to remove it.  Otherwise, it should be fine.
    Backend<?>[] subBackends = backend.getSubordinateBackends();
    if (subBackends != null && subBackends.length > 0)
    {
      ccr.setResultCode(UNWILLING_TO_PERFORM);
      ccr.addMessage(NOTE_CONFIG_BACKEND_CANNOT_REMOVE_BACKEND_WITH_SUBORDINATES.get(backendDN));
      return ccr;
    }

    deregisterBackend(backendDN, backend);

    try
    {
      backend.finalizeBackend();
    }
    catch (Exception e)
    {
      logger.traceException(e);
    }

    configEntry.removeChangeListener(this);

    releaseSharedLock(backend, backend.getBackendID());

    return ccr;
  }

  private void deregisterBackend(DN backendDN, Backend<?> backend)
  {
    for (BackendInitializationListener listener : getBackendInitializationListeners())
    {
      listener.performBackendPreFinalizationProcessing(backend);
    }

    registeredBackends.remove(backendDN);
    DirectoryServer.deregisterBackend(backend);

    for (BackendInitializationListener listener : getBackendInitializationListeners())
    {
      listener.performBackendPostFinalizationProcessing(backend);
    }
  }
}
