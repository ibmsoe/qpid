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

package org.apache.qpid.framing.amqp_8_0;

import org.apache.qpid.framing.AMQMethodBody;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.BasicPublishBody;
import org.apache.qpid.framing.abstraction.AbstractMethodConverter;
import org.apache.qpid.framing.abstraction.MessagePublishInfo;
import org.apache.qpid.framing.abstraction.MessagePublishInfoImpl;
import org.apache.qpid.framing.abstraction.ProtocolVersionMethodConverter;

public class MethodConverter_8_0 extends AbstractMethodConverter implements ProtocolVersionMethodConverter
{
    public MethodConverter_8_0()
    {
        super((byte)8,(byte)0);
    }


    public MessagePublishInfo convertToInfo(AMQMethodBody methodBody)
    {
        final BasicPublishBody publishBody = ((BasicPublishBody) methodBody);

        final AMQShortString exchange = publishBody.getExchange();
        final AMQShortString routingKey = publishBody.getRoutingKey();

        return new MessagePublishInfoImpl(exchange == null ? null : exchange.intern(),
                                          publishBody.getImmediate(),
                                          publishBody.getMandatory(),
                                          routingKey == null ? null : routingKey.intern(false));

    }

    public AMQMethodBody convertToBody(MessagePublishInfo info)
    {

        return new BasicPublishBodyImpl(0,
                                    info.getExchange(),
                                    info.getRoutingKey(),
                                    info.isMandatory(),
                                    info.isImmediate()) ;

    }
}
