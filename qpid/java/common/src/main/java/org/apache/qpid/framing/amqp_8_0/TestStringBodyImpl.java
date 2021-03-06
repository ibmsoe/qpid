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

/*
 * This file is auto-generated by Qpid Gentools v.0.1 - do not modify.
 * Supported AMQP version:
 *   8-0
 */

package org.apache.qpid.framing.amqp_8_0;

import org.apache.qpid.codec.MarkableDataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.qpid.framing.*;
import org.apache.qpid.AMQException;

public class TestStringBodyImpl extends AMQMethodBody_8_0 implements TestStringBody
{
    private static final AMQMethodBodyInstanceFactory FACTORY_INSTANCE = new AMQMethodBodyInstanceFactory()
    {
        public AMQMethodBody newInstance(MarkableDataInput in, long size) throws AMQFrameDecodingException, IOException
        {
            return new TestStringBodyImpl(in);
        }
    };

    public static AMQMethodBodyInstanceFactory getFactory()
    {
        return FACTORY_INSTANCE;
    }

    public static final int CLASS_ID =  120;
    public static final int METHOD_ID = 20;

    // Fields declared in specification
    private final AMQShortString _string1; // [string1]
    private final byte[] _string2; // [string2]
    private final short _operation; // [operation]

    // Constructor
    public TestStringBodyImpl(MarkableDataInput buffer) throws AMQFrameDecodingException, IOException
    {
        _string1 = readAMQShortString( buffer );
        _string2 = readBytes( buffer );
        _operation = readUnsignedByte( buffer );
    }

    public TestStringBodyImpl(
                                AMQShortString string1,
                                byte[] string2,
                                short operation
                            )
    {
        _string1 = string1;
        _string2 = string2;
        _operation = operation;
    }

    public int getClazz()
    {
        return CLASS_ID;
    }

    public int getMethod()
    {
        return METHOD_ID;
    }

    public final AMQShortString getString1()
    {
        return _string1;
    }
    public final byte[] getString2()
    {
        return _string2;
    }
    public final short getOperation()
    {
        return _operation;
    }

    protected int getBodySize()
    {
        int size = 1;
        size += getSizeOf( _string1 );
        size += getSizeOf( _string2 );
        return size;
    }

    public void writeMethodPayload(DataOutput buffer) throws IOException
    {
        writeAMQShortString( buffer, _string1 );
        writeBytes( buffer, _string2 );
        writeUnsignedByte( buffer, _operation );
    }

    public boolean execute(MethodDispatcher dispatcher, int channelId) throws AMQException
	{
    return ((MethodDispatcher_8_0)dispatcher).dispatchTestString(this, channelId);
	}

    public String toString()
    {
        StringBuilder buf = new StringBuilder("[TestStringBodyImpl: ");
        buf.append( "string1=" );
        buf.append(  getString1() );
        buf.append( ", " );
        buf.append( "string2=" );
        buf.append(  getString2() == null  ? "null" : java.util.Arrays.toString( getString2() ) );
        buf.append( ", " );
        buf.append( "operation=" );
        buf.append(  getOperation() );
        buf.append("]");
        return buf.toString();
    }

}
