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
 * Copyright 2006-2009 Sun Microsystems, Inc.
 * Portions Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.server.tasks;

import static org.opends.messages.TaskMessages.*;
import static org.opends.server.config.ConfigConstants.*;
import static org.opends.server.util.StaticUtils.*;

import java.util.List;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.schema.AttributeType;
import org.opends.server.api.ClientConnection;
import org.opends.server.backends.task.Task;
import org.opends.server.backends.task.TaskState;
import org.opends.server.core.DirectoryServer;
import org.opends.server.types.Attribute;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.Entry;
import org.opends.server.types.Operation;
import org.opends.server.types.Privilege;

/**
 * This class provides an implementation of a Directory Server task that can be
 * used to stop the server.
 */
public class ShutdownTask
       extends Task
{
  /** Indicates whether to use an exit code that indicates the server should be restarted. */
  private boolean restart;

  /** The shutdown message that will be used. */
  private LocalizableMessage shutdownMessage;

  @Override
  public LocalizableMessage getDisplayName() {
    return INFO_TASK_SHUTDOWN_NAME.get();
  }

  /**
   * Performs any task-specific initialization that may be required before
   * processing can start.  This default implementation does not do anything,
   * but subclasses may override it as necessary.  This method will be called at
   * the time the task is scheduled, and therefore any failure in this method
   * will be returned to the client.
   *
   * @throws  DirectoryException  If a problem occurs during initialization that
   *                              should be returned to the client.
   */
  @Override
  public void initializeTask()
         throws DirectoryException
  {
    // See if the entry contains a shutdown message.  If so, then use it.
    // Otherwise, use a default message.
    Entry taskEntry = getTaskEntry();

    restart         = false;
    shutdownMessage = INFO_TASK_SHUTDOWN_DEFAULT_MESSAGE.get(taskEntry.getName());

    AttributeType attrType = DirectoryServer.getSchema().getAttributeType(ATTR_SHUTDOWN_MESSAGE);
    List<Attribute> attrList = taskEntry.getAttribute(attrType);
    if (!attrList.isEmpty())
    {
      Attribute attr = attrList.get(0);
      if (!attr.isEmpty())
      {
        String valueString = attr.iterator().next().toString();
        shutdownMessage = INFO_TASK_SHUTDOWN_CUSTOM_MESSAGE.get(taskEntry.getName(), valueString);
      }
    }

    attrType = DirectoryServer.getSchema().getAttributeType(ATTR_RESTART_SERVER);
    attrList = taskEntry.getAttribute(attrType);
    if (!attrList.isEmpty())
    {
      Attribute attr = attrList.get(0);
      if (!attr.isEmpty())
      {
        String valueString = toLowerCase(attr.iterator().next().toString());
        restart = valueString.equals("true") || valueString.equals("yes")
            || valueString.equals("on") || valueString.equals("1");
      }
    }


    // If the client connection is available, then make sure the associated
    // client has either the SERVER_SHUTDOWN or SERVER_RESTART privilege, based
    // on the appropriate action.
    Operation operation = getOperation();
    if (operation != null)
    {
      ClientConnection clientConnection = operation.getClientConnection();
      if (restart)
      {
        if (! clientConnection.hasPrivilege(Privilege.SERVER_RESTART,
                                            operation))
        {
          LocalizableMessage message =
              ERR_TASK_SHUTDOWN_INSUFFICIENT_RESTART_PRIVILEGES.get();
          throw new DirectoryException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS,
                                       message);
        }
      }
      else
      {
        if (! clientConnection.hasPrivilege(Privilege.SERVER_SHUTDOWN,
                                            operation))
        {
          LocalizableMessage message =
              ERR_TASK_SHUTDOWN_INSUFFICIENT_SHUTDOWN_PRIVILEGES.get();
          throw new DirectoryException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS,
                                       message);
        }
      }
    }
  }



  /**
   * Performs the actual core processing for this task.  This method should not
   * return until all processing associated with this task has completed.
   *
   * @return  The final state to use for the task.
   */
  @Override
  public TaskState runTask()
  {
    // This is a unique case in that the shutdown cannot finish until this task
    // is finished, but this task can't really be finished until the shutdown is
    // complete.  To work around this catch-22, we'll spawn a separate thread
    // that will be responsible for really invoking the shutdown and then this
    // method will return.  We'll have to use different types of threads
    // depending on whether we're doing a restart or a shutdown.
    boolean configuredAsService =
      DirectoryServer.isRunningAsWindowsService();
    if (configuredAsService && !restart)
    {
      ShutdownTaskThread shutdownThread =
        new ShutdownTaskThread(shutdownMessage)
      {
        @Override
        public void run()
        {
          org.opends.server.tools.StopWindowsService.main(new String[]{});
        }
      };
      shutdownThread.start();
    }
    else if (restart)
    {
      // Since the process will not be killed, we can proceed exactly the same
      // way with or without windows service configured.
      RestartTaskThread restartThread = new RestartTaskThread(shutdownMessage);
      restartThread.start();
    }
    else
    {
      ShutdownTaskThread shutdownThread =
           new ShutdownTaskThread(shutdownMessage);
      shutdownThread.start();
    }

    return TaskState.COMPLETED_SUCCESSFULLY;
  }
}
