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
    checkArgs(args, 1, Integer.MAX_VALUE);
    String mode = args[0].toLowerCase(Locale.ROOT);
    String[] newArgs = Arrays.copyOfRange(args, 1, args.length);
    switch (mode) {
      case "download-assignments":
        {
          checkArgs(args, 1, 1);
          DownloadAssignments.main(newArgs);
          return;
        }
      case "help":
        {
          printHelp(args.length == 1 ? "" : args[1]);
          return;
        }
      case "ping-all":
        {
          PingAll.main(newArgs);
          return;
        }
      case "ping-repeat":
        {
          checkArgs(args, 1, 1);
          PingRepeat.main(newArgs);
          return;
        }
      case "ping-responder":
        {
          checkArgs(args, 1, 1);
          PingResponder.main(newArgs);
          return;
        }
      default:
        printUsage();
        System.exit(1);
    }
  }

  private static void checkArgs(String[] args, int minArgs, int maxArgs) {
    if (args.length < minArgs || args.length > maxArgs) {
      Util.println("Invalid number of arguments.");
      printUsage();
      System.exit(1);
    }
  }

  private static void printHelp(String mode) {
    switch (mode) {
      case "download-assignments":
        printUsageDownloadAssignments();
        return;
      case "ping-all":
        printUsagePingAll();
        return;
      case "ping-repeat":
        printUsagePingRepeat();
        return;
      case "ping-responder":
        printUsagePingResponder();
        return;
      default:
        printUsage();
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
    Util.println("    - `help [MODE]` for getting more help for a given mode.");
    Util.println("");
  }

  private static void printUsageDownloadAssignments() {
    Util.println("Usage: scion-multiping download-assignments");
    Util.println();
    Util.println(
        "  This tool downloads a list of known ISD/AS assignments and saves it to a file.");
    Util.println("  The output file is called `isd-as-assignments.csv`.");
    Util.println("");
  }

  static void printUsagePingAll() {
    Util.println(
        "Usage: ping-all [--help] [--fastest|--shortest|--shortest_echo|--fastest_sync] [--port <port>] [--shim]");
    Util.println("  --help              Show this help message.");
    Util.println("  --fastest           Use fastest path with SCMP traceroute (default).");
    Util.println(
        "                      The fastest path is determined by running a single traceroute on all path.");
    // Util.println("  --fastest_sync      Use fastest path with SCMP traceroute (synchronous)");
    Util.println("  --shortest          Use shortest path (fewest hops) with SCMP traceroute.");
    // Util.println("  --shortest_echo     Use shortest path with SCMP echo");
    Util.println(
        "  --port <port>       Use specified local port (default " + PingAll.localPort + ").");
    Util.println("  --shim              Start with SHIM enabled (default disabled).");
    Util.println("");
  }

  private static void printUsagePingRepeat() {
    Util.println("Usage: scion-multiping ping-repeat");
    Util.println();
    Util.println(
        "  This tool is used for repeatedly probing (traceroute) multiple paths to multiple ASes.");
    Util.println("  The destination ASes are read from a file `isd-as-assignments.csv`.");
    Util.println("  Other configuration options can be defined in `ping-repeat-config.json`.");
    Util.println("  Results are written to a CSV file `ping-results.csv`.");
    Util.println("  See README.md for more information.");
    Util.println("");
  }

  private static void printUsagePingResponder() {
    Util.println("Usage: scion-multiping ping-responder");
    Util.println();
    Util.println("  This command starts a server that responds to incoming echo requests.");
    Util.println("  It takes a configuration file `ping-responder-config.json` as input.");
    Util.println("  See README.md for more information.");
    Util.println("");
  }
}
