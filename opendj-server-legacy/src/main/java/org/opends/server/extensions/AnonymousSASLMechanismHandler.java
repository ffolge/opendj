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
 * Portions Copyright 2011-2016 ForgeRock AS.
 */
package org.opends.server.extensions;

import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.server.config.server.AnonymousSASLMechanismHandlerCfg;
import org.opends.server.api.SASLMechanismHandler;
import org.opends.server.core.BindOperation;
import org.opends.server.core.DirectoryServer;
import org.opends.server.types.AdditionalLogItem;
import org.opends.server.types.AuthenticationInfo;
import org.opends.server.types.InitializationException;

import static org.opends.server.util.ServerConstants.*;

/**
 * This class provides an implementation of a SASL mechanism, as defined in RFC
 * 4505, that does not perform any authentication.  That is, anyone attempting
 * to bind with this SASL mechanism will be successful and will be given the
 * rights of an unauthenticated user.  The request may or may not include a set
 * of SASL credentials which will serve as trace information.  If provided,
 * then that trace information will be written to the server error log.
 */
public class AnonymousSASLMechanismHandler
       extends SASLMechanismHandler<AnonymousSASLMechanismHandlerCfg>
{
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  /**
   * Creates a new instance of this SASL mechanism handler.  No initialization
   * should be done in this method, as it should all be performed in the
   * <CODE>initializeSASLMechanismHandler</CODE> method.
   */
  public AnonymousSASLMechanismHandler()
  {
    super();
  }

  @Override
  public void initializeSASLMechanismHandler(AnonymousSASLMechanismHandlerCfg configuration)
         throws ConfigException, InitializationException
  {
    // No real implementation is required.  Simply register with the Directory
    // Server for the ANONYMOUS mechanism.
    DirectoryServer.registerSASLMechanismHandler(SASL_MECHANISM_ANONYMOUS, this);
  }

  @Override
  public void finalizeSASLMechanismHandler()
  {
    DirectoryServer.deregisterSASLMechanismHandler(SASL_MECHANISM_ANONYMOUS);
  }

  @Override
  public void processSASLBind(BindOperation bindOperation)
  {
    // See if the client provided SASL credentials including trace information.
    // If so, then write it to the access log as additional log information, and
    // as an informational message to the error log.
    ByteString saslCredentials = bindOperation.getSASLCredentials();
    if (saslCredentials != null)
    {
      String credString = saslCredentials.toString();
      if (credString.length() > 0)
      {
        bindOperation.addAdditionalLogItem(AdditionalLogItem.quotedKeyValue(
            getClass(), "trace", credString));
      }
    }

    // Authenticate the client anonymously and indicate that the bind was successful.
    AuthenticationInfo authInfo = new AuthenticationInfo();
    bindOperation.setAuthenticationInfo(authInfo);
    bindOperation.setResultCode(ResultCode.SUCCESS);
  }

  @Override
  public boolean isPasswordBased(String mechanism)
  {
    // This is not a password-based mechanism.
    return false;
  }

  @Override
  public boolean isSecure(String mechanism)
  {
    // This is not a secure mechanism.
    return false;
  }
}
