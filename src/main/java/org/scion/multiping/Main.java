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

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import org.scion.multiping.util.Util;

public class Main {

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      printUsage();
      System.exit(1);
    }
    String mode = args[0].toLowerCase(Locale.ROOT);
    String[] newArgs = Arrays.copyOfRange(args, 1, args.length);
    switch (mode) {
      case "download-assignments":
        {
          DownloadAssignments.main(newArgs);
          return;
        }
      case "ping-all":
        {
          PingAll.main(newArgs);
          return;
        }
      case "ping-repeat":
        {
          PingRepeat.main(newArgs);
          return;
        }
      case "ping-responder":
        {
          PingResponder.main(newArgs);
          return;
        }
      default:
        printUsage();
        System.exit(1);
    }
  }

  private static void printUsage() {
    Util.println("Usage: scion-multiping [MODE]");
    Util.println("where MODE is one of: ");
    Util.println("    - `download-assignments` for downloading a list of known ISD/AS assignments");
    Util.println(
        "    - `ping-all` for sending a single traceroute to all known ASes along the shortest path (default behaviour)");
    Util.println(
        "    - `ping-repeat` for repeatedly probing (traceroute) multiple paths to multiple ASes.");
    Util.println(
        "    - `ping-responder` for starting a server that responds to incoming echo requests.");
    Util.println("");
  }
}
