// Copyright 2025 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.multiping.util;

import java.util.ArrayList;
import java.util.List;
import org.scion.jpan.ScionUtil;

public class Helper {

  public static List<ParseAssignments.HostEntry> isdAsList() {
    List<ParseAssignments.HostEntry> result = new ArrayList<>();
    result.add(new ParseAssignments.HostEntry(ScionUtil.parseIA("1-123"), "AS-1-123"));
    result.add(new ParseAssignments.HostEntry(ScionUtil.parseIA("1-234"), "AS-1-234"));
    result.add(new ParseAssignments.HostEntry(ScionUtil.parseIA("2-123"), "AS-2-123"));
    return result;
  }
}
