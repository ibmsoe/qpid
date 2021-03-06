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
package org.apache.qpid.framing;

import org.apache.qpid.AMQException;
import org.apache.qpid.protocol.AMQVersionAwareProtocolSession;

import java.io.DataOutput;
import java.io.IOException;

public interface AMQBody
{
    public  byte getFrameType();
    
    /** 
     * Get the size of the body
     * @return unsigned short
     */
    public abstract int getSize();
    
    public void writePayload(DataOutput buffer) throws IOException;
    
    void handle(final int channelId, final AMQVersionAwareProtocolSession amqProtocolSession) throws AMQException;
}
