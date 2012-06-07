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



require(["dojo/dom", "dojo/domReady!"],
                    function(dom)
              {

                  var footerHTML = "<div class=\"footer\"><p>&#xA9; 2004-"+ (new Date()).getFullYear()
                                   +" The Apache Software Foundation.<br />Apache Qpid, Qpid, Apache, the Apache feather logo, and the "
                                   +"Apache Qpid project logo are trademarks of The Apache Software Foundation.<br />"
                                   +"All other marks mentioned may be trademarks or registered trademarks of their respective owners."
                                   +"</p></div>";

                  var footerDiv = dom.byId("footer");
                  footerDiv.innerHTML = footerHTML;

              });
