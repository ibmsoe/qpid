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

#include "config.h"
#include "XmlExchange.h"

#include "DeliverableMessage.h"

#include "qpid/log/Statement.h"
#include "qpid/framing/FieldTable.h"
#include "qpid/framing/FieldValue.h"
#include "qpid/framing/reply_exceptions.h"

#include <xercesc/framework/MemBufInputSource.hpp>

#include <xqilla/context/ItemFactory.hpp>
#include <xqilla/xqilla-simple.hpp>

#include <iostream>
#include <sstream>

using namespace qpid::framing;
using namespace qpid::sys;
using qpid::management::Manageable;

namespace qpid {
namespace broker {

XmlExchange::XmlExchange(const string& _name, Manageable* _parent) : Exchange(_name, _parent)
{
    if (mgmtExchange.get() != 0)
        mgmtExchange->set_type (typeName);
}

XmlExchange::XmlExchange(const std::string& _name, bool _durable,
                         const FieldTable& _args, Manageable* _parent) :
    Exchange(_name, _durable, _args, _parent)
{
    if (mgmtExchange.get() != 0)
        mgmtExchange->set_type (typeName);
}

/*
 *  Use the name of the query as the binding key.
 *
 *  The first time a given name is used in a binding, the query body
 *  must be provided.After that, no query body should be present.
 *  
 *  To modify an installed query, the user must first unbind the
 *  existing query, then replace it by binding again with the same
 *  name.
 *
 */

      // #### TODO: The Binding should take the query text
      // #### only. Consider encapsulating the entire block, including
      // #### the if condition.
      

bool XmlExchange::bind(Queue::shared_ptr queue, const string& routingKey, const FieldTable* bindingArguments)
{
    string queryText = bindingArguments->getString("xquery");

    try {
        RWlock::ScopedWlock l(lock);
        XmlBinding::vector& bindings(bindingsMap[routingKey]);
        XmlBinding::vector::iterator i;

        for (i = bindings.begin(); i != bindings.end(); i++)
            if ((*i)->queue == queue)
                break;

        if (i == bindings.end()) {

            Query query(xqilla.parse(X(queryText.c_str())));
            XmlBinding::shared_ptr binding(new XmlBinding (routingKey, queue, this, query));
            XmlBinding::vector bindings(1, binding);
            bindingsMap[routingKey] = bindings;
            QPID_LOG(trace, "Bound successfully with query: " << queryText );

            if (mgmtExchange.get() != 0) {
                mgmtExchange->inc_bindingCount();
            }
            return true;
        } else{
            return false;
        }
    }
    catch (XQException& e) {
        throw InternalErrorException(QPID_MSG("Could not parse xquery:"+ queryText));
    }
    catch (...) {
        throw InternalErrorException(QPID_MSG("Unexpected error - Could not parse xquery:"+ queryText));
    }
}

bool XmlExchange::unbind(Queue::shared_ptr queue, const string& routingKey, const FieldTable* /*args*/)
{
    RWlock::ScopedWlock l(lock);
    XmlBinding::vector& bindings(bindingsMap[routingKey]);
    XmlBinding::vector::iterator i;

    for (i = bindings.begin(); i != bindings.end(); i++)
        if ((*i)->queue == queue)
            break;

    if (i < bindings.end()) {
        bindings.erase(i);
        if (bindings.empty()) {
            bindingsMap.erase(routingKey);
        }
        if (mgmtExchange.get() != 0) {
            mgmtExchange->dec_bindingCount();
        }
        return true;
    } else {
        return false;
    }
}

bool XmlExchange::matches(Query& query, Deliverable& msg, const qpid::framing::FieldTable* args) 
{
  // ### TODO: Need istream for frameset
  // Hack alert - the following code does not work for really large messages

  string msgContent;

  try {
    msg.getMessage().getFrames().getContent(msgContent);

    QPID_LOG(trace, "matches: query is [" << UTF8(query->getQueryText()) << "]");
    QPID_LOG(trace, "matches: message content is [" << msgContent << "]");

    boost::scoped_ptr<DynamicContext> context(query->createDynamicContext());
    if (!context.get()) {
        throw InternalErrorException(QPID_MSG("Query context looks munged ..."));
    }

    XERCES_CPP_NAMESPACE::MemBufInputSource xml((XMLByte*) msgContent.c_str(), msgContent.length(), "input" );
    Sequence seq(context->parseDocument(xml));

    if (args) {
        FieldTable::ValueMap::const_iterator v = args->begin();
        for(; v != args->end(); ++v) {
            // ### TODO: Do types properly
            if (v->second->convertsTo<std::string>()) {
                QPID_LOG(trace, "XmlExchange, external variable: " << v->first << " = " << v->second->getData().getString().c_str());
                Item::Ptr value = context->getItemFactory()->createString(X(v->second->getData().getString().c_str()), context.get());
                context->setExternalVariable(X(v->first.c_str()), value);
            }
        }
    }

    if(!seq.isEmpty() && seq.first()->isNode()) {
      context->setContextItem(seq.first());
      context->setContextPosition(1);
      context->setContextSize(1);
    }
    Result result = query->execute(context.get());
    return result->getEffectiveBooleanValue(context.get(), 0);
  }
  catch (XQException& e) {
    QPID_LOG(warning, "Could not parse XML content (or message headers):" << msgContent);
    return 0;
  }
  catch (...) {
    QPID_LOG(warning, "Unexpected error routing message: " << msgContent);
    return 0;
  }
  return 0;
}

void XmlExchange::route(Deliverable& msg, const string& routingKey, const FieldTable* args)
{
    try {
        RWlock::ScopedRlock l(lock);
        XmlBinding::vector& bindings(bindingsMap[routingKey]);
        XmlBinding::vector::iterator i;
        int count(0);

        for (i = bindings.begin(); i != bindings.end(); i++) {

            if ((*i)->xquery && matches((*i)->xquery, msg, args)) {   // Overly defensive? There should always be a query ...
                msg.deliverTo((*i)->queue);
                count++;
                QPID_LOG(trace, "Delivered to queue" );

                if ((*i)->mgmtBinding.get() != 0)
                    (*i)->mgmtBinding->inc_msgMatched ();
            }

            if(!count){
                QPID_LOG(warning, "XMLExchange " << getName() << ": could not route message with query " << routingKey);
                if (mgmtExchange.get() != 0) {
                    mgmtExchange->inc_msgDrops  ();
                    mgmtExchange->inc_byteDrops (msg.contentSize ());
                }
            }
            else {
                if (mgmtExchange.get() != 0) {
                    mgmtExchange->inc_msgRoutes  (count);
                    mgmtExchange->inc_byteRoutes (count * msg.contentSize ());
                }
            }

            if (mgmtExchange.get() != 0) {
                mgmtExchange->inc_msgReceives  ();
                mgmtExchange->inc_byteReceives (msg.contentSize ());
            }
        }
    }
    catch (...) {
        QPID_LOG(warning, "XMLExchange " << getName() << ": exception routing message with query " << routingKey);
    }
      

}


bool XmlExchange::isBound(Queue::shared_ptr queue, const string* const routingKey, const FieldTable* const) 
{
    XmlBinding::vector::iterator j;

    if (routingKey) {
      XmlBindingsMap::iterator i = bindingsMap.find(*routingKey);

      if (i == bindingsMap.end())
	return false;
      if (!queue)
	return true;
      for (j = i->second.begin(); j != i->second.end(); j++)
	if ((*j)->queue == queue)
	  return true;
    } else if (!queue) {
      //if no queue or routing key is specified, just report whether any bindings exist
      return bindingsMap.size() > 0;
    } else {
      for (XmlBindingsMap::iterator i = bindingsMap.begin(); i != bindingsMap.end(); i++)
	for (j = i->second.begin(); j != i->second.end(); j++)
	  if ((*j)->queue == queue)
	    return true;
      return false;
    }

    return false;
}


XmlExchange::~XmlExchange() 
{
    bindingsMap.clear();
}

const std::string XmlExchange::typeName("xml");
 
}
}
