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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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

  private static final int LOCAL_PORT = 30041;
  private static final boolean STOP_SHIM = true;

  static {
    config.tryICMP = false;
    if (SHOW_ONLY_ICMP) {
      DELAYED_PRINT = true;
    }
  }

  private final Set<Long> listedAs = new HashSet<>();
  private final Set<Long> seenAs = new HashSet<>();
  private final ResultSummary summary = new ResultSummary();

  private final ScionProvider service;
  private final Policy policy;

  enum Policy {
    /** Fastest path using SCMP traceroute */
    FASTEST_TR,
    /** Fastest path using SCMP async traceroute */
    FASTEST_TR_ASYNC,
    /** Shortest path using SCMP traceroute */
    SHORTEST_TR,
    /** Fastest path using SCMP echo */
    SHORTEST_ECHO
  }

  private static final Policy DEFAULT_POLICY = Policy.FASTEST_TR_ASYNC;
  private static final boolean SHOW_PATH = false;

  PingAll(Policy policy, ScionProvider service) {
    this.policy = policy;
    this.service = service;
  }

  public static void main(String[] args) throws IOException {
    PRINT = true;
    // System.setProperty(Constants.PROPERTY_DNS_SEARCH_DOMAINS, "ethz.ch.");
    System.setProperty(Constants.PROPERTY_SHIM, STOP_SHIM ? "false" : "true"); // disable SHIM

    println("Settings:");
    println("  Path policy = " + DEFAULT_POLICY);
    println("  ICMP=" + config.tryICMP);
    println("  printOnlyICMP=" + SHOW_ONLY_ICMP);

    PingAll pingAll = new PingAll(DEFAULT_POLICY, ScionProvider.defaultProvider(LOCAL_PORT));
    pingAll.run();
    pingAll.summary.prettyPrint();
  }

  ResultSummary run() throws IOException {
    List<ParseAssignments.HostEntry> allASes = service.getIsdAsEntries();
    // remove entry for local AS
    long localAS = Scion.defaultService().getLocalIsdAs();
    allASes = allASes.stream().filter(e -> e.getIsdAs() != localAS).collect(Collectors.toList());
    // Process all ASes
    for (ParseAssignments.HostEntry e : allASes) {
      print(ScionUtil.toStringIA(e.getIsdAs()) + " \"" + e.getName() + "\"  ");
      runAS(e);
      listedAs.add(e.getIsdAs());
    }

    // Try to identify ASes that occur in any paths but that are not on the public list.
    for (Long isdAs : seenAs) {
      if (!listedAs.contains(isdAs)) {
        summary.incSeenButNotListed();
      }
    }
    return summary;
  }

  private void runAS(ParseAssignments.HostEntry remote) throws IOException {
    summary.incAsTried();
    // Dummy address. The traceroute will contact the control service IP instead.
    InetSocketAddress destinationAddress =
        new InetSocketAddress(InetAddress.getByAddress(new byte[] {0, 0, 0, 0}), 30041);
    int nPaths;
    Scmp.TimedMessage[] msgs = new Scmp.TimedMessage[REPEAT];
    Ref<Path> bestPath = Ref.empty();
    try {
      List<Path> paths = service.getPaths(remote.getIsdAs(), destinationAddress);
      if (paths.isEmpty()) {
        String src = ScionUtil.toStringIA(service.getLocalIsdAs());
        String dst = ScionUtil.toStringIA(remote.getIsdAs());
        if (!SHOW_ONLY_ICMP) {
          println("WARNING: No path found from " + src + " to " + dst);
        }
        summary.incAsNoPathFound();
        summary.add(new Result(remote, Result.State.NO_PATH));
        return;
      }
      nPaths = paths.size();
      msgs[0] = findPaths(paths, bestPath);
      // bestPath is null if all paths have timed out
      if (msgs[0] != null && bestPath.get() != null && REPEAT > 1) {
        try (ScionProvider.Sync sender = service.getSync()) {
          for (int i = 1; i < msgs.length; i++) {
            if (bestPath.get() == null) {
              break;
            }
            List<Scmp.TracerouteMessage> messages = sender.sendTracerouteRequest(bestPath.get());
            msgs[i] = messages.get(messages.size() - 1);
          }
        }
      }
    } catch (ScionRuntimeException e) {
      println("ERROR: " + e.getMessage());
      summary.incAsError();
      summary.add(new Result(remote, Result.State.ERROR));
      return;
    }
    Result result = new Result(remote, msgs[0], bestPath.get(), nPaths);
    summary.add(result);

    if (msgs[0] == null) {
      return;
    }

    // ICMP ping
    StringBuilder icmpMs = new StringBuilder();
    for (int i = 0; i < REPEAT; i++) {
      String icmpMsStr = ICMP.pingICMP(msgs[0].getPath().getRemoteAddress(), config);
      icmpMs.append(icmpMsStr).append(" ");
      if (icmpMsStr.startsWith("TIMEOUT") || icmpMsStr.startsWith("N/A")) {
        break;
      }
    }
    result.setICMP(icmpMs.toString());

    // output
    int nHops = PathRawParser.create(msgs[0].getPath().getRawPath()).getHopCount();
    String addr = msgs[0].getPath().getRemoteAddress().getHostAddress();
    print(addr + "  nPaths=" + nPaths + "  nHops=" + nHops + "  time= ");
    for (Scmp.TimedMessage m : msgs) {
      if (m == null) {
        print("N/A ");
        continue;
      }
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
    if (msgs[0].isTimedOut()) {
      summary.incAsTimeout();
    } else {
      summary.incAsSuccess();
    }
  }

  private Scmp.TimedMessage findPaths(List<Path> paths, Ref<Path> bestOut) {
    switch (policy) {
      case FASTEST_TR:
        return findFastestTR(paths, bestOut);
      case FASTEST_TR_ASYNC:
        return findFastestTRasync(paths, bestOut);
      case SHORTEST_TR:
        return findShortestTR(paths, bestOut);
      case SHORTEST_ECHO:
        return findShortestEcho(paths, bestOut);
      default:
        throw new UnsupportedOperationException();
    }
  }

  private Scmp.EchoMessage findShortestEcho(List<Path> paths, Ref<Path> refBest) {
    Path path = PathPolicy.MIN_HOPS.filter(paths).get(0);
    refBest.set(path);
    ByteBuffer bb = ByteBuffer.allocate(0);
    try (ScionProvider.Sync sender = service.getSync()) {
      summary.incPathTried();
      Scmp.EchoMessage msg = sender.sendEchoRequest(path, bb);

      if (msg.isTimedOut()) {
        summary.incPathTimeout();
        return msg;
      }

      summary.incPathSuccess();
      return msg;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      summary.incAsError();
      return null;
    }
  }

  private Scmp.TracerouteMessage findShortestTR(List<Path> paths, Ref<Path> refBest) {
    Path path = PathPolicy.MIN_HOPS.filter(paths).get(0);
    refBest.set(path);
    try (ScionProvider.Sync sender = service.getSync()) {
      summary.incPathTried();
      List<Scmp.TracerouteMessage> messages = sender.sendTracerouteRequest(path);

      for (Scmp.TracerouteMessage msg : messages) {
        seenAs.add(msg.getIsdAs());
      }

      Scmp.TracerouteMessage msg = messages.get(messages.size() - 1);
      if (msg.isTimedOut()) {
        summary.incPathTimeout();
        return msg;
      }

      summary.incPathSuccess();
      return msg;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      summary.incAsError();
      return null;
    }
  }

  private Scmp.TracerouteMessage findFastestTR(List<Path> paths, Ref<Path> refBest) {
    Scmp.TracerouteMessage best = null;
    try (ScionProvider.Sync sender = service.getSync()) {
      for (Path path : paths) {
        summary.incPathTried();
        List<Scmp.TracerouteMessage> messages = sender.sendTracerouteRequest(path);

        for (Scmp.TracerouteMessage msg : messages) {
          seenAs.add(msg.getIsdAs());
        }

        Scmp.TracerouteMessage msg = messages.get(messages.size() - 1);
        if (msg.isTimedOut()) {
          summary.incPathTimeout();
          return msg;
        }

        summary.incPathSuccess();

        if (best == null || msg.getNanoSeconds() < best.getNanoSeconds()) {
          best = msg;
          refBest.set(path);
        }
      }
      return best;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      summary.incAsError();
      return null;
    }
  }

  private Scmp.TracerouteMessage findFastestTRasync(List<Path> paths, Ref<Path> refBest) {
    ConcurrentHashMap<Integer, Scmp.TimedMessage> messages = new ConcurrentHashMap<>();
    CountDownLatch barrier = new CountDownLatch(paths.size());
    ScmpSenderAsync.ResponseHandler handler =
        new ScmpSenderAsync.ResponseHandler() {
          @Override
          public void onResponse(Scmp.TimedMessage msg) {
            System.out.println(
                "Received response for "
                    + ScionUtil.toStringIA(msg.getIdentifier())
                    + " "
                    + msg.getSequenceNumber());
            barrier.countDown();
            messages.put(msg.getIdentifier(), msg);
          }

          @Override
          public void onTimeout(Scmp.TimedMessage msg) {
            System.out.println(
                "Timeout for "
                    + ScionUtil.toStringIA(msg.getIdentifier())
                    + " "
                    + msg.getSequenceNumber()
                    + " "
                    + msg.getPath().getRemoteAddress());
            barrier.countDown();
            messages.put(msg.getIdentifier(), msg);
          }

          @Override
          public void onError(Scmp.ErrorMessage msg) {
            summary.incAsError();
            barrier.countDown();
          }

          @Override
          public void onException(Throwable t) {
            summary.incAsError();
            barrier.countDown();
          }
        };

    // Send all requests
    try (ScionProvider.Async sender = service.getAsync(handler)) {
      for (Path path : paths) {
        summary.incPathTried();
        int x = sender.sendTracerouteLast(path);
        System.out.println("Sending " + x + "     " + ScionUtil.toStringPath(path.getRawPath()) + " to " + path.getRemoteAddress());
      }
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      summary.incAsError();
      return null;
    }

    // Wait for all messages to be received
    try {
      if (!barrier.await(1100, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException(
            "Missing messages: " + barrier.getCount() + "/" + paths.size());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }

    Scmp.TracerouteMessage best = null;
    for (Scmp.TimedMessage tm : messages.values()) {
      Scmp.TracerouteMessage msg = (Scmp.TracerouteMessage) tm;
      seenAs.add(msg.getIsdAs());

      if (msg.isTimedOut()) {
        summary.incPathTimeout();
        if (best == null) {
          best = msg;
        }
        continue;
      }

      summary.incPathSuccess();

      if (best == null || msg.getNanoSeconds() < best.getNanoSeconds()) {
        best = msg;
        refBest.set(msg.getRequest().getPath());
      }
    }
    return best;
  }
}
