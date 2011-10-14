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
package org.apache.qpid.messaging.address.amqp_0_10;

import java.util.Map;

public class Binding
{
    String exchange;
    String bindingKey;
    String queue;
    Map<String,Object> args;
    
    public Binding(String exchange,
                   String queue,
                   String bindingKey,
                   Map<String,Object> args)
    {
        this.exchange = exchange;
        this.queue = queue;
        this.bindingKey = bindingKey;
        this.args = args;
    }
    
    public String getExchange() 
    {
        return exchange;
    }

    public String getQueue() 
    {
        return queue;
    }
    
    public String getBindingKey() 
    {
        return bindingKey;
    }
    
    public Map<String, Object> getArgs() 
    {
        return args;
    }
}
