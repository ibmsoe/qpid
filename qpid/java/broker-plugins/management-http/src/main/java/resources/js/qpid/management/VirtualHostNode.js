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
define(["dojo/_base/xhr",
        "dojo/parser",
        "dojo/query",
        "dojo/_base/connect",
        "dijit/registry",
        "dojox/html/entities",
        "qpid/common/properties",
        "qpid/common/updater",
        "qpid/common/util",
        "qpid/common/formatter",
        "qpid/common/UpdatableStore",
        "qpid/management/addQueue",
        "qpid/management/addExchange",
        "dojox/grid/EnhancedGrid",
        "dojo/domReady!"],
       function (xhr, parser, query, connect, registry, entities, properties, updater, util, formatter, UpdatableStore, addQueue, addExchange, EnhancedGrid) {

           function VirtualHostNode(name, parent, controller)
           {
               this.name = name;
               this.controller = controller;
               this.modelObj = { type: "virtualhostnode", name: name, parent: parent};
           }

           VirtualHostNode.prototype.getTitle = function()
           {
               return "VirtualHostNode: " + this.name;
           };

           VirtualHostNode.prototype.open = function(contentPane)
           {
               var that = this;
               this.contentPane = contentPane;
               xhr.get({url: "showVirtualHostNode.html",
                        sync: true,
                        load:  function(data) {
                            contentPane.containerNode.innerHTML = data;
                            parser.parse(contentPane.containerNode);
                            that.onOpen(contentPane.containerNode)
                       }});

           };

           VirtualHostNode.prototype.onOpen = function(containerNode)
           {
             var that = this;
             this.stopNodeButton = registry.byNode(query(".stopNodeButton", containerNode)[0]);
             this.startNodeButton = registry.byNode(query(".startNodeButton", containerNode)[0]);
             this.editNodeButton = registry.byNode(query(".editNodeButton", containerNode)[0]);
             this.deleteNodeButton = registry.byNode(query(".deleteNodeButton", containerNode)[0]);
             this.deleteNodeButton.on("click",
                 function(e)
                 {
                   if (confirm("Deletion of virtual host node will delete both configuration and message data.\n\n"
                           + "Are you sure you want to delete virtual host node '" + entities.encode(String(that.name)) + "'?"))
                   {
                     if (util.sendRequest("api/latest/virtualhostnode/" + encodeURIComponent( that.name) , "DELETE"))
                     {
                       that.destroy();
                     }
                   }
                 }
             );
             this.startNodeButton.on("click",
               function(event)
               {
                 that.startNodeButton.set("disabled", true);
                 util.sendRequest("api/latest/virtualhostnode/" + encodeURIComponent(that.name),
                         "PUT", {desiredState: "ACTIVE"});
               });

             this.stopNodeButton.on("click",
               function(event)
               {
                 if (confirm("Stopping the node will also shutdown the virtual host. "
                         + "Are you sure you want to stop virtual host node '"
                         + entities.encode(String(that.name)) +"'?"))
                 {
                     that.stopNodeButton.set("disabled", true);
                     util.sendRequest("api/latest/virtualhostnode/" + encodeURIComponent(that.name),
                             "PUT", {desiredState: "STOPPED"});
                 }
               });
             this.vhostNodeUpdater = new Updater(containerNode, this.modelObj, this);
             this.vhostNodeUpdater.update();

             updater.add( this.vhostNodeUpdater );
           }

           VirtualHostNode.prototype.close = function()
           {
               updater.remove( this.vhostNodeUpdater );
           };

           VirtualHostNode.prototype.destroy = function()
           {
             this.close();
             this.contentPane.onClose()
             this.controller.tabContainer.removeChild(this.contentPane);
             this.contentPane.destroyRecursive();
           }

           function Updater(domNode, nodeObject, virtualHostNode)
           {
               this.virtualHostNode = virtualHostNode;
               var that = this;

               function findNode(name)
               {
                   return query("." + name, domNode)[0];
               }

               function storeNodes(names)
               {
                   for(var i = 0; i < names.length; i++)
                   {
                       that[names[i]] = findNode(names[i]);
                   }
               }

               storeNodes(["name", "state", "type", "messageStoreProviderYes","messageStoreProviderNo"]);
               this.detailsDiv = findNode("virtualhostnodedetails");

               this.query = "api/latest/virtualhostnode/" + encodeURIComponent(nodeObject.name);
          }

           Updater.prototype.update = function()
           {
               var that = this;
               xhr.get({url: this.query, sync: properties.useSyncGet, handleAs: "json"}).then(
                   function(data)
                   {
                     that.updateUI(data[0]);
                   }
               );
           };

           Updater.prototype.updateUI = function(data)
           {
             this.virtualHostNode.startNodeButton.set("disabled", data.state != "STOPPED");
             this.virtualHostNode.stopNodeButton.set("disabled", data.state != "ACTIVE");

             this.name.innerHTML = entities.encode(String(data[ "name" ]));
             this.state.innerHTML = entities.encode(String(data[ "state" ]));
             this.type.innerHTML = entities.encode(String(data[ "type" ]));
             this.messageStoreProviderYes.style.display = data.messageStoreProvider? "block" : "none";
             this.messageStoreProviderNo.style.display = data.messageStoreProvider? "none" : "block";
             if (!this.details)
             {
               var that = this;
               require(["qpid/management/virtualhostnode/" + data.type.toLowerCase() + "/show"],
                 function(VirtualHostNodeDetails)
                 {
                   that.details = new VirtualHostNodeDetails({containerNode:that.detailsDiv, parent: that.virtualHostNode});
                   that.details.update(data);
                 }
               );
             }
             else
             {
               this.details.update(data);
             }
           }

           return VirtualHostNode;
       });
