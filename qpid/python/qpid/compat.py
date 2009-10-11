#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

import sys

try:
  set = set
except NameError:
  from sets import Set as set

try:
  from socket import SHUT_RDWR
except ImportError:
  SHUT_RDWR = 2

try:
  from traceback import format_exc
except ImportError:
  import traceback
  def format_exc():
    return "".join(traceback.format_exception(*sys.exc_info()))

if tuple(sys.version_info[0:2]) < (2, 4):
  from select import select as old_select
  def select(rlist, wlist, xlist, timeout=None):
    return old_select(list(rlist), list(wlist), list(xlist), timeout)
else:
  from select import select
