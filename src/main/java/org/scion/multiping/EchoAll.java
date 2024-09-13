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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
public class EchoAll {
  private static final Config config = new Config();

  static {
    config.tryICMP = false;
  }

  private final int localPort;

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

  private static final Policy POLICY = Policy.SHORTEST_TR;
  private static final boolean SHOW_PATH = true;

  public EchoAll(int localPort) {
    this.localPort = localPort;
  }

  public static void main(String[] args) throws IOException {
    PRINT = true;
    System.setProperty(Constants.PROPERTY_DNS_SEARCH_DOMAINS, "ethz.ch.");

    // Local port must be 30041 for networks that expect a dispatcher
    EchoAll demo = new EchoAll(30041);
    // List<ParseAssignments.HostEntry> list = ParseAssignments.getList();
    List<ParseAssignments.HostEntry> list = DownloadAssignments.getList();
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
    Result maxHops = results.stream().max((o1, o2) -> o1.getHopCount() - o2.getHopCount()).get();
    Result maxPaths = results.stream().max((o1, o2) -> o1.getPathCount() - o2.getPathCount()).get();

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
    Scmp.TimedMessage msg;
    Ref<Path> bestPath = Ref.empty();
    try {
      List<Path> paths = service.getPaths(remote.getIsdAs(), destinationAddress);
      if (paths.isEmpty()) {
        String src = ScionUtil.toStringIA(service.getLocalIsdAs());
        String dst = ScionUtil.toStringIA(remote.getIsdAs());
        println("WARNING: No path found from " + src + " to " + dst);
        nAsNoPathFound++;
        results.add(new Result(remote, Result.State.NO_PATH));
        return;
      }
      nPaths = paths.size();
      msg = findPaths(paths, bestPath);
    } catch (ScionRuntimeException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      results.add(new Result(remote, Result.State.ERROR));
      return;
    }
    Result result = new Result(remote, msg, bestPath.get(), nPaths);
    results.add(result);

    if (msg == null) {
      return;
    }

    // ICMP ping
    String icmpMs = ICMP.pingICMP(msg.getPath().getRemoteAddress(), config);
    result.setICMP(icmpMs);

    // output
    double millis = round(msg.getNanoSeconds() / (double) 1_000_000, 2);
    int nHops = PathRawParser.create(msg.getPath().getRawPath()).getHopCount();
    String addr = msg.getPath().getRemoteAddress().getHostAddress();
    String out = addr + "  nPaths=" + nPaths + "  nHops=" + nHops;
    out += "  time=" + millis + "ms" + "  ICMP=" + icmpMs;
    if (SHOW_PATH) {
      out += "  " + ScionUtil.toStringPath(bestPath.get().getMetadata());
    }
    println(out);
    if (msg.isTimedOut()) {
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
    int id = 0;
    try (ScmpChannel scmpChannel = Scmp.createChannel(localPort)) {
      nPathTried++;
      Scmp.EchoMessage msg = scmpChannel.sendEchoRequest(path, id, bb);
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
    try (ScmpChannel scmpChannel = Scmp.createChannel(localPort)) {
      nPathTried++;
      List<Scmp.TracerouteMessage> messages = scmpChannel.sendTracerouteRequest(path);
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
    try (ScmpChannel scmpChannel = Scmp.createChannel(localPort)) {
      for (Path path : paths) {
        nPathTried++;
        List<Scmp.TracerouteMessage> messages = scmpChannel.sendTracerouteRequest(path);
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
