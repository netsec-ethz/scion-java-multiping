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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionUtil;

class DonwloaderTest {

  @Test
  void findAssignments() {
    List<ParseAssignments.HostEntry> list = DownloadAssignmentsFromWeb.getList();
    assertFalse(list.isEmpty());

    boolean found = false;
    for (ParseAssignments.HostEntry e : list) {
      if (e.getIsdAs() == ScionUtil.parseIA("64-2:0:9")) {
        found = true;
        break;
      }
    }
    assertTrue(found);
  }
}
