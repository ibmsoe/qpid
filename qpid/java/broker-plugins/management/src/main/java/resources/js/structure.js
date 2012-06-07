/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


function BrokerTreeModel()
{
    this.query = "/rest/structure";

    this.onChildrenChange = function(parent, children)
    {
          // fired when the set of children for an object change
    };

    this.onChange = function(object)
    {
          // fired when the properties of an object change
    };

    this.onDelete =  function(object)
    {
          // fired when an object is deleted
    };

}



BrokerTreeModel.prototype.buildModel = function(data)
{
    this.model = data;

};

BrokerTreeModel.prototype.updateModel = function(data)
{
    var that = this;

    function checkForChanges(oldData, data)
    {
        var propName;
        if(oldData.name != data.name)
        {
            that.onChange(data);
        }

        var childChanges = false;
        // Iterate over old childTypes, check all are in new
        for(propName in oldData)
        {
            if(oldData.hasOwnProperty(propName)) {
                var oldChildren = oldData[ propName ];
                if(isArray(oldChildren)) {

                    var newChildren = data[ propName ];

                    if(!(newChildren && isArray(newChildren))) {
                        childChanges = true;
                    } else {
                        var subChanges = false;
                        // iterate over elements in array, make sure in both, in which case recurse
                        for(var i = 0; i < oldChildren.length; i++) {
                            var matched = false;
                            for(var j = 0; j < newChildren.length; j++) {
                                if(oldChildren[i].id == newChildren[j].id) {
                                    checkForChanges(oldChildren[i], newChildren[j]);
                                    matched = true;
                                    break;
                                }
                            }
                            if(!matched) {
                                subChanges = true;
                            }
                        }
                        if(subChanges == true || oldChildren.length != newChildren.length) {
                            that.onChildrenChange({ id: data.id+propName, _dummyChild: propName, data: data }, newChildren);
                        }
                    }
                }
            }
        }

        for(propName in data) {
            if(data.hasOwnProperty(propName)) {
                var prop = data[ propName ];
                if(isArray(prop)) {
                    if(!(oldData[ propName ] && isArray(oldData[propName]))) {
                        childChanges = true;
                    }
                }
            }
        }

        if(childChanges) {
            var children = [];
            that.getChildren(data, function(theChildren) { children = theChildren });
            that.onChildrenChange(data, children);
        }
    }

    var oldData = this.model;
    this.model = data;

    checkForChanges(oldData, data);
};


BrokerTreeModel.prototype.fetchItemByIdentity = function(id) {

    function fetchItem(id, data) {
        var propName;

        if(data.id == id) {
            return data;
        } else if(id.indexOf(data.id) == 0) {
            return { id: id, _dummyChild: id.substring(id.length), data: data };
        } else {
            for(propName in data) {
                if(data.hasOwnProperty(propName)) {
                    var prop = data[ propName ];
                    if(isArray(prop)) {
                        for(var i = 0 ; i < prop.length; i++) {
                            var theItem = fetchItem(id, prop[i]);
                            if( theItem ) {
                                return theItem;
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    return fetchItem(id, this.model);
};

BrokerTreeModel.prototype.getChildren = function(parentItem, onComplete) {

    if(parentItem) {
        if(parentItem._dummyChild) {
            onComplete(parentItem.data[ parentItem._dummyChild ]);
        } else {
            var children = [];
            for(var propName in parentItem) {
                if(parentItem.hasOwnProperty(propName)) {
                    var prop = parentItem[ propName ];

                    if(isArray(prop)) {
                        children.push({ id: parentItem.id+propName, _dummyChild: propName, data: parentItem });
                    }
                }
            }
            onComplete( children );
        }
    } else {
        onComplete([]);
    }
};

BrokerTreeModel.prototype.getIdentity = function (theItem)
{
    if(theItem)
    {
        return theItem.id;
    }

};

BrokerTreeModel.prototype.getLabel = function (theItem)
{
    if(theItem)
    {
        if(theItem._dummyChild)
        {
            return theItem._dummyChild;
        }
        else
        {
            return theItem.name;
        }
    }
    else
    {
        return "";
    }
};

BrokerTreeModel.prototype.getRoot = function (onItem)
{
    onItem( this.model );
};

BrokerTreeModel.prototype.mayHaveChildren = function (theItem) {
    if(theItem) {
        if(theItem._dummyChild) {
            return true;
        } else {
            for(var propName in theItem) {
                if(theItem.hasOwnProperty(propName)) {
                    var prop = theItem[ propName ];
                    if(isArray(prop)) {
                        return true;
                    }
                }
            }
            return false;
        }
    } else {
        return false;
    }
};

require(["dojo/io-query", "dojo/domReady!"],
    function(ioQuery) {

        BrokerTreeModel.prototype.relocate = function (theItem) {

            function findItemDetails(theItem, details, type, object) {
                if(theItem.id == object.id) {
                    details.type = type;
                    details[ type ] = object.name;
                } else {
                    details[ type ] = object.name;

                    // iterate over children
                    for(var propName in object) {
                        if(object.hasOwnProperty(propName)) {
                            var prop = object[ propName ];
                            if(isArray(prop)) {
                                for(var i = 0 ; i < prop.length; i++) {
                                    findItemDetails(theItem, details, propName.substring(0, propName.length-1),prop[i]);

                                    if(details.type) {
                                        break;
                                    }
                                }
                            }
                            if(details.type) {
                                break;
                            }
                        }
                    }

                    if(!details.type) {
                        details[ type ] = null;
                    }
                }
            }

            var details = new Object();

            findItemDetails(theItem, details, "broker", this.model);

            var uri;


            if(details.type == "broker") {
                uri = "/broker";
            } else if(details.type == "virtualhost") {
                uri = "/vhost?" + ioQuery.objectToQuery({ vhost: details.virtualhost });
            }
            else if(details.type == "exchange")
            {
                uri = "/exchange?" + ioQuery.objectToQuery({ vhost: details.virtualhost , exchange: details.exchange });
            }
            else if(details.type == "queue")
            {
                uri = "/queue?" + ioQuery.objectToQuery({ vhost: details.virtualhost , queue: details.queue});
            }
            else if(details.type == "connection")
            {
                uri = "/connection?" + ioQuery.objectToQuery({ vhost: details.virtualhost , connection: details.connection});
            }
            else if(details.type == 'port')
            {
                uri = "/port?" + ioQuery.objectToQuery({ port: details.port});
            }

            if(uri)
            {
                window.location = uri;
            }
        };
    });

var structure;

require(["dojo/_base/xhr", "dojo/domReady!"],
	     function(xhr)
	     {


             BrokerTreeModel.prototype.update = function()
             {
                 var thisObj = this;

                 xhr.get({url: this.query, sync: useSyncGet, handleAs: "json"})
                     .then(function(data)
                           {
                               if(thisObj.model)
                               {
                                   thisObj.updateModel(data);
                               }
                               else
                               {
                                   thisObj.buildModel(data);
                               }
                           });

             };

             structure = new BrokerTreeModel();
             structure.update();

             require(["dijit/Tree"], function(Tree) {
                 tree = new Tree({ model: structure }, "structure");
                 tree.on("dblclick",
                         function(object)
                         {
                             if(object && !object._dummyChild)
                             {
                                structure.relocate(object);
                             }

                         }, true);
                 tree.startup();
             });

             updateList.push(structure);
         });