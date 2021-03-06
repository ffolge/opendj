#!/bin/sh
#
# The contents of this file are subject to the terms of the Common Development and
# Distribution License (the License). You may not use this file except in compliance with the
# License.
#
# You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
# specific language governing permission and limitations under the License.
#
# When distributing Covered Software, include this CDDL Header Notice in each file and include
# the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
# Header, with the fields enclosed by brackets [] replaced by your own identifying
# information: "Portions Copyright [year] [name of copyright owner]".
#
# Copyright 2008-2010 Sun Microsystems, Inc.
# Portions Copyright 2011-2013 ForgeRock AS.


# This script is used to invoke processes that might be run on server or
# in client mode (depending on the state of the server and the arguments
# passed).  It should not be invoked directly by end users.
if test -z "${OPENDJ_INVOKE_CLASS}"
then
  echo "ERROR:  OPENDJ_INVOKE_CLASS environment variable is not set."
  exit 1
fi


# Capture the current working directory so that we can change to it later.
# Then capture the location of this script and the Directory Server install
# and instance  root so that we can use them to create appropriate paths.
WORKING_DIR=`pwd`

cd "`dirname "${0}"`"
SCRIPT_DIR=`pwd`

cd ..
INSTALL_ROOT=`pwd`
export INSTALL_ROOT

cd "${WORKING_DIR}"

OLD_SCRIPT_NAME=${SCRIPT_NAME}
SCRIPT_NAME=${OLD_SCRIPT_NAME}.online
export SCRIPT_NAME

# We keep this values to reset the environment before calling _script-util.sh
# for the second time.
ORIGINAL_JAVA_ARGS=${OPENDJ_JAVA_ARGS}
ORIGINAL_JAVA_HOME=${OPENDJ_JAVA_HOME}
ORIGINAL_JAVA_BIN=${OPENDJ_JAVA_BIN}

# Set environment variables
SCRIPT_UTIL_CMD=set-full-environment
export SCRIPT_UTIL_CMD
.  "${INSTALL_ROOT}/lib/_script-util.sh"
RETURN_CODE=$?
if test ${RETURN_CODE} -ne 0
then
  exit ${RETURN_CODE}
fi

MUST_CALL_AGAIN="false"

SCRIPT_NAME_ARG=-Dorg.opends.server.scriptName=${OLD_SCRIPT_NAME}
export SCRIPT_NAME_ARG

# Check whether is local or remote
"${OPENDJ_JAVA_BIN}" ${OPENDJ_JAVA_ARGS} ${SCRIPT_ARGS}  ${SCRIPT_NAME_ARG} "${OPENDJ_INVOKE_CLASS}" \
     --configFile "${INSTANCE_ROOT}/config/config.ldif" --testIfOffline "${@}"
EC=${?}
if test ${EC} -eq 51
then
  # Set the original values that the user had on the environment in order to be
  # sure that the script works with the proper arguments (in particular
  # if the user specified not to overwrite the environment).
  OPENDJ_JAVA_ARGS=${ORIGINAL_JAVA_ARGS}
  OPENDJ_JAVA_HOME=${ORIGINAL_JAVA_HOME}
  OPENDJ_JAVA_BIN=${ORIGINAL_JAVA_BIN}

  # Set the environment to use the offline properties
  SCRIPT_NAME=${OLD_SCRIPT_NAME}.offline
  export SCRIPT_NAME
  .  "${INSTALL_ROOT}/lib/_script-util.sh"
  RETURN_CODE=$?
  if test ${RETURN_CODE} -ne 0
  then
    exit ${RETURN_CODE}
  fi
  MUST_CALL_AGAIN="true"
else
  if test ${EC} -eq 52
  then
    MUST_CALL_AGAIN="true"
  else
    # This is likely a problem with the provided arguments.
    exit ${EC}
  fi
fi

if test ${MUST_CALL_AGAIN} = "true"
then
  SCRIPT_NAME_ARG=-Dorg.opends.server.scriptName=${OLD_SCRIPT_NAME}
  export SCRIPT_NAME_ARG

  # Launch the server utility.
  "${OPENDJ_JAVA_BIN}" ${OPENDJ_JAVA_ARGS} ${SCRIPT_ARGS} ${SCRIPT_NAME_ARG} "${OPENDJ_INVOKE_CLASS}" \
       --configFile "${INSTANCE_ROOT}/config/config.ldif" "${@}"
fi
