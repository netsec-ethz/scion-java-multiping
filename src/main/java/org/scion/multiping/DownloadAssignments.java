// Copyright 2024 ETH Zurich
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

package org.scion.multiping;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import org.scion.jpan.*;
import org.scion.multiping.util.DownloadAssignmentsFromWeb;
import org.scion.multiping.util.ParseAssignments;
import org.scion.multiping.util.Util;

/**
 * This program takes a list of ISD/AS addresses and tries to measure latency to all of them. It
 * will also attempt an ICMP ping for comparison.<br>
 * The list is derived from <a
 * href="https://docs.anapaya.net/en/latest/resources/isd-as-assignments/">here</a> and is locally
 * stored in ISD-AS-Assignments.csv, see {@link ParseAssignments}.java.
 *
 * <p>There are several options for executing measurements (see "Policy"):<br>
 * - SCMP traceroute vs SCMP echo<br>
 * - Fastest vs shortest
 *
 * <p>Shortest: Report results on the path with the fewest hops. The number of hops can be evaluated
 * locally, so this is very fast.
 *
 * <p>Fastest: Report the path with the lowest latency. This takes much longer because it will try
 * all available paths before it can report on the best path.
 */
public class DownloadAssignments {
  private DownloadAssignments() {}

  public static void main(String[] args) throws IOException {
    Util.PRINT = true;
    String fileName = args.length > 0 ? args[0] : "isd-as-assignments.csv";
    File csvOutputFile = new File(fileName);
    List<ParseAssignments.HostEntry> list = DownloadAssignmentsFromWeb.getList();
    try (PrintWriter pw = new PrintWriter(csvOutputFile)) {
      for (ParseAssignments.HostEntry e : list) {
        Util.println(ScionUtil.toStringIA(e.getIsdAs()) + " \"" + e.getName() + "\"  ");
        pw.println(ScionUtil.toStringIA(e.getIsdAs()) + ",\"" + e.getName() + "\"");
      }
    }
    Util.println(list.size() + " ISD/AS assignments written to " + csvOutputFile);
  }
}
