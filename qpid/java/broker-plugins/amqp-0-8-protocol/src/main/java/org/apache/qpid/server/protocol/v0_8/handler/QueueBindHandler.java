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
package org.apache.qpid.server.protocol.v0_8.handler;

import java.security.AccessControlException;
import java.util.Map;

import org.apache.log4j.Logger;

import org.apache.qpid.AMQException;
import org.apache.qpid.exchange.ExchangeDefaults;
import org.apache.qpid.framing.AMQMethodBody;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.FieldTable;
import org.apache.qpid.framing.MethodRegistry;
import org.apache.qpid.framing.QueueBindBody;
import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.server.exchange.ExchangeImpl;
import org.apache.qpid.server.protocol.v0_8.AMQChannel;
import org.apache.qpid.server.protocol.v0_8.AMQProtocolSession;
import org.apache.qpid.server.protocol.v0_8.state.AMQStateManager;
import org.apache.qpid.server.protocol.v0_8.state.StateAwareMethodListener;
import org.apache.qpid.server.queue.AMQQueue;
import org.apache.qpid.server.virtualhost.VirtualHostImpl;

public class QueueBindHandler implements StateAwareMethodListener<QueueBindBody>
{
    private static final Logger _log = Logger.getLogger(QueueBindHandler.class);

    private static final QueueBindHandler _instance = new QueueBindHandler();

    public static QueueBindHandler getInstance()
    {
        return _instance;
    }

    private QueueBindHandler()
    {
    }

    public void methodReceived(AMQStateManager stateManager, QueueBindBody body, int channelId) throws AMQException
    {
        AMQProtocolSession protocolConnection = stateManager.getProtocolSession();
        VirtualHostImpl virtualHost = protocolConnection.getVirtualHost();
        AMQChannel channel = protocolConnection.getChannel(channelId);

        if (channel == null)
        {
            throw body.getChannelNotFoundException(channelId);
        }

        final AMQQueue queue;
        final AMQShortString routingKey;

        final AMQShortString queueName = body.getQueue();

        if (queueName == null)
        {

            queue = channel.getDefaultQueue();

            if (queue == null)
            {
                throw body.getChannelException(AMQConstant.NOT_FOUND, "No default queue defined on channel and queue was null");
            }

            if (body.getRoutingKey() == null)
            {
                routingKey = AMQShortString.valueOf(queue.getName());
            }
            else
            {
                routingKey = body.getRoutingKey().intern();
            }
        }
        else
        {
            queue = virtualHost.getQueue(queueName.toString());
            routingKey = body.getRoutingKey() == null ? AMQShortString.EMPTY_STRING : body.getRoutingKey().intern();
        }

        if (queue == null)
        {
            throw body.getChannelException(AMQConstant.NOT_FOUND, "Queue " + queueName + " does not exist.");
        }

        if(isDefaultExchange(body.getExchange()))
        {
            throw body.getConnectionException(AMQConstant.NOT_ALLOWED, "Cannot bind the queue " + queueName + " to the default exchange");
        }

        final String exchangeName = body.getExchange().toString();

        final ExchangeImpl exch = virtualHost.getExchange(exchangeName);
        if (exch == null)
        {
            throw body.getChannelException(AMQConstant.NOT_FOUND, "Exchange " + exchangeName + " does not exist.");
        }


        try
        {

            Map<String,Object> arguments = FieldTable.convertToMap(body.getArguments());
            String bindingKey = String.valueOf(routingKey);

            if (!exch.isBound(bindingKey, arguments, queue))
            {

                if(!exch.addBinding(bindingKey, queue, arguments) && ExchangeDefaults.TOPIC_EXCHANGE_CLASS.equals(exch.getType()))
                {
                    exch.replaceBinding(bindingKey, queue, arguments);
                }
            }
        }
        catch (AccessControlException e)
        {
            throw body.getConnectionException(AMQConstant.ACCESS_REFUSED, e.getMessage());
        }

        if (_log.isInfoEnabled())
        {
            _log.info("Binding queue " + queue + " to exchange " + exch + " with routing key " + routingKey);
        }
        if (!body.getNowait())
        {
            channel.sync();
            MethodRegistry methodRegistry = protocolConnection.getMethodRegistry();
            AMQMethodBody responseBody = methodRegistry.createQueueBindOkBody();
            protocolConnection.writeFrame(responseBody.generateFrame(channelId));

        }
    }

    protected boolean isDefaultExchange(final AMQShortString exchangeName)
    {
        return exchangeName == null || exchangeName.equals(AMQShortString.EMPTY_STRING);
    }
}
