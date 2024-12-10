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

import static org.scion.multiping.util.Util.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import org.scion.jpan.*;
import org.scion.jpan.internal.PathRawParser;
import org.scion.multiping.util.*;

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
public class PingAll {
  private static final int REPEAT = 3;
  private static final boolean SHOW_ONLY_ICMP = false;
  private static final Config config = new Config();

  static {
    config.tryICMP = false;
    if (SHOW_ONLY_ICMP) {
      DELAYED_PRINT = true;
    }
  }

  private int nAsTried = 0;
  private int nAsSuccess = 0;
  private int nAsError = 0;
  private int nAsTimeout = 0;
  private int nAsNoPathFound = 0;

  private int nPathTried = 0;
  private int nPathSuccess = 0;
  private int nPathTimeout = 0;

  private static final Set<Long> listedAs = new HashSet<>();
  private static final Set<Long> seenAs = new HashSet<>();
  private static final List<Result> results = new ArrayList<>();

  private enum Policy {
    /** Fastest path using SCMP traceroute */
    FASTEST_TR,
    /** Shortest path using SCMP traceroute */
    SHORTEST_TR,
    /** Fastest path using SCMP echo */
    FASTEST_ECHO,
    /** Fastest path using SCMP echo */
    SHORTEST_ECHO
  }

  private static final Policy POLICY = Policy.SHORTEST_TR; // SHORTEST_TR;
  private static final boolean SHOW_PATH = !true;

  public static void main(String[] args) throws IOException {
    PRINT = true;
    // System.setProperty(Constants.PROPERTY_DNS_SEARCH_DOMAINS, "ethz.ch.");

    println("Settings:");
    println("  Path policy = " + POLICY);
    println("  ICMP=" + config.tryICMP);
    println("  printOnlyICMP=" + SHOW_ONLY_ICMP);

    PingAll demo = new PingAll();
    List<ParseAssignments.HostEntry> list = DownloadAssignmentsFromWeb.getList();
    for (ParseAssignments.HostEntry e : list) {
      print(ScionUtil.toStringIA(e.getIsdAs()) + " \"" + e.getName() + "\"  ");
      demo.runDemo(e);
      listedAs.add(e.getIsdAs());
    }

    // Try to identify ASes that occur in any paths but that are not on the public list.
    int nSeenButNotListed = 0;
    for (Long isdAs : seenAs) {
      if (!listedAs.contains(isdAs)) {
        nSeenButNotListed++;
      }
    }

    // max:
    Result maxPing =
        results.stream().max((o1, o2) -> (int) (o1.getPingMs() - o2.getPingMs())).get();
    Result maxHops = results.stream().max(Comparator.comparingInt(Result::getHopCount)).get();
    Result maxPaths = results.stream().max(Comparator.comparingInt(Result::getPathCount)).get();

    println("");
    println("Max hops  = " + maxHops.getHopCount() + ":    " + maxHops);
    println("Max ping  = " + round(maxPing.getPingMs(), 2) + "ms:    " + maxPing);
    println("Max paths = " + maxPaths.getPathCount() + ":    " + maxPaths);

    println("");
    println("AS Stats:");
    println(" all        = " + demo.nAsTried);
    println(" success    = " + demo.nAsSuccess);
    println(" no path    = " + demo.nAsNoPathFound);
    println(" timeout    = " + demo.nAsTimeout);
    println(" error      = " + demo.nAsError);
    println(" not listed = " + nSeenButNotListed);
    println("Path Stats:");
    println(" all        = " + demo.nPathTried);
    println(" success    = " + demo.nPathSuccess);
    println(" timeout    = " + demo.nPathTimeout);
    println("ICMP Stats:");
    println(" all        = " + ICMP.nIcmpTried);
    println(" success    = " + ICMP.nIcmpSuccess);
    println(" timeout    = " + ICMP.nIcmpTimeout);
    println(" error      = " + ICMP.nIcmpError);
  }

  private void runDemo(ParseAssignments.HostEntry remote) throws IOException {
    nAsTried++;
    ScionService service = Scion.defaultService();
    // Dummy address. The traceroute will contact the control service IP instead.
    InetSocketAddress destinationAddress =
        new InetSocketAddress(InetAddress.getByAddress(new byte[] {1, 2, 3, 4}), 12345);
    int nPaths;
    Scmp.TimedMessage[] msg = new Scmp.TimedMessage[REPEAT];
    Ref<Path> bestPath = Ref.empty();
    try {
      List<Path> paths = service.getPaths(remote.getIsdAs(), destinationAddress);
      if (paths.isEmpty()) {
        String src = ScionUtil.toStringIA(service.getLocalIsdAs());
        String dst = ScionUtil.toStringIA(remote.getIsdAs());
        if (!SHOW_ONLY_ICMP) {
          println("WARNING: No path found from " + src + " to " + dst);
        }
        nAsNoPathFound++;
        results.add(new Result(remote, Result.State.NO_PATH));
        return;
      }
      nPaths = paths.size();
      msg[0] = findPaths(paths, bestPath);
      if (msg[0] != null && REPEAT > 1) {
        try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
          for (int i = 1; i < msg.length; i++) {
            List<Scmp.TracerouteMessage> messages = sender.sendTracerouteRequest(bestPath.get());
            msg[i] = messages.get(messages.size() - 1);
          }
        }
      }
    } catch (ScionRuntimeException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      results.add(new Result(remote, Result.State.ERROR));
      return;
    }
    Result result = new Result(remote, msg[0], bestPath.get(), nPaths);
    results.add(result);

    if (msg[0] == null) {
      return;
    }

    // ICMP ping
    StringBuilder icmpMs = new StringBuilder();
    for (int i = 0; i < REPEAT; i++) {
      String icmpMsStr = ICMP.pingICMP(msg[0].getPath().getRemoteAddress(), config);
      icmpMs.append(icmpMsStr).append(" ");
      if (icmpMsStr.startsWith("TIMEOUT") || icmpMsStr.startsWith("N/A")) {
        break;
      }
    }
    result.setICMP(icmpMs.toString());

    // output
    int nHops = PathRawParser.create(msg[0].getPath().getRawPath()).getHopCount();
    String addr = msg[0].getPath().getRemoteAddress().getHostAddress();
    print(addr + "  nPaths=" + nPaths + "  nHops=" + nHops + "  time= ");
    for (Scmp.TimedMessage m : msg) {
      double millis = round(m.getNanoSeconds() / (double) 1_000_000, 2);
      print(millis + "ms ");
    }
    String icmpStr = icmpMs.toString();
    print("  ICMP= " + icmpStr);
    if (SHOW_PATH) {
      print("  " + ScionUtil.toStringPath(bestPath.get().getMetadata()));
    }
    if (SHOW_ONLY_ICMP && (icmpStr.startsWith("N/A") || icmpStr.startsWith("TIMEOUT"))) {
      clearPrintQueue();
    } else {
      println();
    }
    if (msg[0].isTimedOut()) {
      nAsTimeout++;
    } else {
      nAsSuccess++;
    }
  }

  private Scmp.TimedMessage findPaths(List<Path> paths, Ref<Path> bestOut) {
    switch (POLICY) {
      case FASTEST_TR:
        return findFastestTR(paths, bestOut);
      case SHORTEST_TR:
        return findShortestTR(paths, bestOut);
      case SHORTEST_ECHO:
        return findShortestEcho(paths, bestOut);
      default:
        throw new UnsupportedOperationException();
    }
  }

  private Scmp.EchoMessage findShortestEcho(List<Path> paths, Ref<Path> refBest) {
    Path path = PathPolicy.MIN_HOPS.filter(paths);
    refBest.set(path);
    ByteBuffer bb = ByteBuffer.allocate(0);
    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      nPathTried++;
      Scmp.EchoMessage msg = sender.sendEchoRequest(path, bb);
      if (msg == null) {
        println(" -> local AS, no timing available");
        nPathSuccess++;
        nAsSuccess++;
        return null;
      }

      if (msg.isTimedOut()) {
        nPathTimeout++;
        return msg;
      }

      nPathSuccess++;
      return msg;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }
  }

  private Scmp.TracerouteMessage findShortestTR(List<Path> paths, Ref<Path> refBest) {
    Path path = PathPolicy.MIN_HOPS.filter(paths);
    refBest.set(path);
    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      nPathTried++;
      List<Scmp.TracerouteMessage> messages = sender.sendTracerouteRequest(path);
      if (messages.isEmpty()) {
        println(" -> local AS, no timing available");
        nPathSuccess++;
        nAsSuccess++;
        return null;
      }

      for (Scmp.TracerouteMessage msg : messages) {
        seenAs.add(msg.getIsdAs());
      }

      Scmp.TracerouteMessage msg = messages.get(messages.size() - 1);
      if (msg.isTimedOut()) {
        nPathTimeout++;
        return msg;
      }

      nPathSuccess++;
      return msg;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }
  }

  private Scmp.TracerouteMessage findFastestTR(List<Path> paths, Ref<Path> refBest) {
    Scmp.TracerouteMessage best = null;
    try (ScmpSender sender = Scmp.newSenderBuilder().build()) {
      for (Path path : paths) {
        nPathTried++;
        List<Scmp.TracerouteMessage> messages = sender.sendTracerouteRequest(path);
        if (messages.isEmpty()) {
          println(" -> local AS, no timing available");
          nPathSuccess++;
          nAsSuccess++;
          return null;
        }

        for (Scmp.TracerouteMessage msg : messages) {
          seenAs.add(msg.getIsdAs());
        }

        Scmp.TracerouteMessage msg = messages.get(messages.size() - 1);
        if (msg.isTimedOut()) {
          nPathTimeout++;
          return msg;
        }

        nPathSuccess++;

        if (best == null || msg.getNanoSeconds() < best.getNanoSeconds()) {
          best = msg;
          refBest.set(path);
        }
      }
      return best;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }
  }
}
