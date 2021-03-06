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
package org.apache.qpid.server.security.auth.manager;

import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.ManagedAttribute;
import org.apache.qpid.server.model.ManagedObject;
import org.apache.qpid.server.model.TrustStore;

@ManagedObject( category = false, type = "SimpleLDAP" )
public interface SimpleLDAPAuthenticationManager<X extends SimpleLDAPAuthenticationManager<X>> extends AuthenticationProvider<X>
{
    String PROVIDER_TYPE = "SimpleLDAP";
    String TRUST_STORE = "trustStore";

    @ManagedAttribute( description = "LDAP server URL" )
    String getProviderUrl();

    @ManagedAttribute( description = "LDAP authentication URL")
    String getProviderAuthUrl();

    @ManagedAttribute( description = "Search context")
    String getSearchContext();

    @ManagedAttribute( description = "Search filter")
    String getSearchFilter();

    @ManagedAttribute( description = "Bind without search")
    boolean isBindWithoutSearch();

    @ManagedAttribute( description = "LDAP context factory")
    String getLdapContextFactory();

    @ManagedAttribute( description = "Trust store name")
    TrustStore getTrustStore();

    @ManagedAttribute( description = "(Optional) username for authenticated search")
    String getSearchUsername();

    @ManagedAttribute( description = "(Optional) password for authenticated search", secure = true)
    String getSearchPassword();
}
