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
package org.apache.qpid.server.model.adapter;

import java.security.Principal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.server.model.AbstractConfiguredObject;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.Connection;
import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Session;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.StateTransition;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.protocol.AMQConnectionModel;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.protocol.SessionModelListener;
import org.apache.qpid.server.util.Action;

public final class ConnectionAdapter extends AbstractConfiguredObject<ConnectionAdapter> implements Connection<ConnectionAdapter>,
                                                                                             SessionModelListener
{
    private AMQConnectionModel _connection;

    private State _state = State.ACTIVE;

    public ConnectionAdapter(final AMQConnectionModel conn)
    {
        super(parentsMap(conn.getVirtualHost()),createAttributes(conn));
        _connection = conn;

        conn.addDeleteTask(new Action()
        {
            @Override
            public void performAction(final Object object)
            {
                conn.removeDeleteTask(this);
                deleted();
            }
        });

        conn.addSessionListener(this);
    }

    private static Map<String, Object> createAttributes(final AMQConnectionModel _connection)
    {
        Map<String,Object> attributes = new HashMap<String, Object>();
        attributes.put(ID, UUID.randomUUID());
        attributes.put(NAME, _connection.getRemoteAddressString().replaceAll("/", ""));
        attributes.put(DURABLE, false);
        return attributes;
    }

    @Override
    public String getClientId()
    {
        return _connection.getClientId();
    }

    @Override
    public String getClientVersion()
    {
        return _connection.getClientVersion();
    }

    @Override
    public boolean isIncoming()
    {
        return true;
    }

    @Override
    public String getLocalAddress()
    {
        return null;
    }

    @Override
    public String getPrincipal()
    {
        final Principal authorizedPrincipal = _connection.getAuthorizedPrincipal();
        return authorizedPrincipal == null ? null : authorizedPrincipal.getName();
    }

    @Override
    public String getRemoteAddress()
    {
        return _connection.getRemoteAddressString();
    }

    @Override
    public String getRemoteProcessName()
    {
        return null;
    }

    @Override
    public String getRemoteProcessPid()
    {
        return null;
    }

    @Override
    public long getSessionCountLimit()
    {
        return _connection.getSessionCountLimit();
    }

    @Override
    public Transport getTransport()
    {
        return _connection.getTransport();
    }

    @Override
    public Port getPort()
    {
        return _connection.getPort();
    }

    public Collection<Session> getSessions()
    {
        return getChildren(Session.class);
    }

    @StateTransition( currentState = State.ACTIVE, desiredState = State.DELETED)
    private void doDelete()
    {
        _connection.close(AMQConstant.CONNECTION_FORCED, "Connection closed by external action");
        deleted();
        _state = State.DELETED;
    }

    public State getState()
    {
        return _state;
    }


    @Override
    public <C extends ConfiguredObject> C addChild(Class<C> childClass, Map<String, Object> attributes, ConfiguredObject... otherParents)
    {
        if(childClass == Session.class)
        {
            throw new IllegalStateException();
        }
        else
        {
            throw new IllegalArgumentException("Cannot create a child of class " + childClass.getSimpleName());
        }

    }

    @Override
    public long getBytesIn()
    {
        return _connection.getDataReceiptStatistics().getTotal();
    }

    @Override
    public long getBytesOut()
    {
        return _connection.getDataDeliveryStatistics().getTotal();
    }

    @Override
    public long getMessagesIn()
    {
        return _connection.getMessageReceiptStatistics().getTotal();
    }

    @Override
    public long getMessagesOut()
    {
        return _connection.getMessageDeliveryStatistics().getTotal();
    }

    @Override
    public long getLastIoTime()
    {
        return _connection.getLastIoTime();
    }

    @Override
    public int getSessionCount()
    {
        return _connection.getSessionModels().size();
    }

    @Override
    public void sessionAdded(final AMQSessionModel<?, ?> session)
    {
        SessionAdapter adapter = new SessionAdapter(this, session);
        adapter.create();
        childAdded(adapter);
    }

    @Override
    public void sessionRemoved(final AMQSessionModel<?, ?> session)
    {
        // SessionAdapter installs delete task to cause session model object to delete
    }
}
