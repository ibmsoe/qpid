/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.connection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.log4j.Logger;

import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.server.protocol.AMQConnectionModel;

public class ConnectionRegistry implements IConnectionRegistry
{
    private List<AMQConnectionModel> _registry = new CopyOnWriteArrayList<AMQConnectionModel>();

    private Logger _logger = Logger.getLogger(ConnectionRegistry.class);
    private final Collection<RegistryChangeListener> _listeners =
            new ArrayList<RegistryChangeListener>();

    public void initialise()
    {
        // None required
    }

    /** Close all of the currently open connections. */
    public void close()
    {
        close(IConnectionRegistry.BROKER_SHUTDOWN_REPLY_TEXT);
    }

    public void close(final String replyText)
    {
        synchronized(this)
        {
            for(AMQConnectionModel conn : _registry)
            {
                conn.stop();
            }
        }
        if (_logger.isDebugEnabled())
        {
            _logger.debug("Closing connection registry :" + _registry.size() + " connections.");
        }
        while (!_registry.isEmpty())
        {
            AMQConnectionModel connection = _registry.get(0);

            try
            {
                connection.close(AMQConstant.CONNECTION_FORCED, replyText);
            }
            catch (Exception e)
            {
                //remove this connection to ensure that we don't loop forever if it fails to close
                _registry.remove(connection);

                _logger.warn("Exception closing connection " + connection.getConnectionId() + " from " + connection.getRemoteAddressString(), e);
            }
        }
    }

    public void registerConnection(AMQConnectionModel connection)
    {
        synchronized (this)
        {
            _registry.add(connection);
            synchronized (_listeners)
            {
                for(RegistryChangeListener listener : _listeners)
                {
                    listener.connectionRegistered(connection);
                }
            }
        }
    }

    public void deregisterConnection(AMQConnectionModel connection)
    {
        synchronized (this)
        {
            _registry.remove(connection);

            synchronized (_listeners)
            {
                for(RegistryChangeListener listener : _listeners)
                {
                    listener.connectionUnregistered(connection);
                }
            }
        }
    }

    public void addRegistryChangeListener(RegistryChangeListener listener)
    {
        synchronized (_listeners)
        {
            _listeners.add(listener);
        }
    }

    public List<AMQConnectionModel> getConnections()
    {
        synchronized (this)
        {
            return new ArrayList<AMQConnectionModel>(_registry);
        }
    }
}
